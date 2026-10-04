package org.familyhealthcare.service.careplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.familyhealthcare.service.careplan.CareExecutionReport.*;
import static org.familyhealthcare.service.careplan.CareExecutionReportAccess.*;
import static org.familyhealthcare.service.careplan.CareExecutionReportContracts.*;

/** One immutable, independently scoped database snapshot. This class never calls a clinical command. */
@Service
public class CareExecutionReportProjector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STATES = Arrays.asList("OPEN", "NEEDS_HELP", "SUBMITTED", "CONFIRMED");
    private static final List<String> ACTION_EVENTS = Arrays.asList("RECEIPT_SUBMITTED", "RECEIPT_RETURNED", "RECEIPT_CONFIRMED", "HELP_REQUESTED", "FOLLOW_UP_RECORDED");
    private static final String PUBLIC_SQL = "'PLAN_PUBLISHED','REVISION_PUBLISHED','RECEIPT_SUBMITTED','HELP_REQUESTED','FOLLOW_UP_RECORDED','RECEIPT_RETURNED','RECEIPT_CONFIRMED','PLAN_CANCELLED','PLAN_CLOSED'";
    private final JdbcTemplate jdbc;
    private final CarePlanAuthorizationService auth;
    private final TransactionTemplate snapshot;

    public CareExecutionReportProjector(JdbcTemplate jdbc, CarePlanAuthorizationService auth, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc); this.auth = Objects.requireNonNull(auth);
        snapshot = new TransactionTemplate(Objects.requireNonNull(transactions));
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setTimeout((int) REQUEST_TIMEOUT.getSeconds());
    }

    public Projection project(long actorId, Request request, Access access, CareExecutionReportBudget budget) {
        Objects.requireNonNull(request); Objects.requireNonNull(access); Objects.requireNonNull(budget).checkTime();
        TransactionTemplate requestSnapshot = new TransactionTemplate(snapshot.getTransactionManager(), snapshot);
        requestSnapshot.setTimeout(budget.remainingQuerySeconds());
        try {
            return requestSnapshot.execute(status -> new Read(actorId, request, access, budget, verifyTransaction()).project());
        } catch (org.springframework.dao.QueryTimeoutException | org.springframework.transaction.TransactionTimedOutException timeout) {
            throw CareExecutionReportException.timeout();
        }
    }

    /** JDBC's read-only hint is ignored by H2. Production MySQL must actually acknowledge it. */
    private String verifyTransaction() {
        DataSource ds = Objects.requireNonNull(jdbc.getDataSource());
        Connection c = DataSourceUtils.getConnection(ds);
        try {
            String product = c.getMetaData().getDatabaseProductName();
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !DataSourceUtils.isConnectionTransactional(c, ds) || c.getAutoCommit()
                    || c.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ
                    || (!"H2".equals(product) && !c.isReadOnly())) {
                throw new IllegalStateException("Report projection requires an effective independent read-only REPEATABLE_READ transaction.");
            }
            if (!"MySQL".equals(product) && !"H2".equals(product)) throw new IllegalStateException("Unsupported report snapshot database.");
            return "H2".equals(product) ? "CURRENT_TIMESTAMP" : "UTC_TIMESTAMP(6)";
        } catch (SQLException e) { throw new IllegalStateException("Cannot verify report snapshot transaction.", e); }
        finally { DataSourceUtils.releaseConnection(c, ds); }
    }

    private final class Read {
        private final long actor;
        private final Request request;
        private final Access access;
        private final CareExecutionReportBudget budget;
        private final String dbNow;
        private final Set<EvidenceKey> manifest = new LinkedHashSet<>();
        private final Map<Long, Boolean> assignees = new HashMap<>();
        private Instant asOf;

        Read(long actor, Request request, Access access, CareExecutionReportBudget budget, String dbNow) {
            this.actor=actor; this.request=request; this.access=access; this.budget=budget; this.dbNow=dbNow;
        }

        Projection project() {
            // This is deliberately the first table read: it establishes the InnoDB consistent snapshot.
            List<Patient> patients = query("SELECT id,name,"+dbNow+" AS snapshot_at FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",
                    (rs,n)->{asOf=instant(rs,"snapshot_at"); return new Patient(rs.getLong("id"),text(rs.getString("name")));},request.getPatientId());
            if (patients.size()!=1 || asOf==null) throw inconsistent();
            boolean current = request.getFormat()!=Format.EVENTS_CSV;
            boolean period = request.getFormat()!=Format.ACTIONS_CSV;
            List<CurrentAction> actions = current ? currentActions() : Collections.emptyList();
            List<CurrentAction> attention = new ArrayList<>();
            long open=0,help=0,submitted=0,confirmed=0,overdue=0,supplement=0;
            for(CurrentAction action:actions) {
                switch(action.getStatus()) {case "OPEN":open++;break;case "NEEDS_HELP":help++;break;case "SUBMITTED":submitted++;break;case "CONFIRMED":confirmed++;break;default:throw inconsistent();}
                if(action.isOverdue())overdue++; if(action.isNeedsSupplement())supplement++;
                if(action.isOverdue()||action.isNeedsSupplement()||"NEEDS_HELP".equals(action.getStatus())||"SUBMITTED".equals(action.getStatus()))attention.add(action);
            }
            List<PeriodEvent> events = period ? periodEvents() : Collections.emptyList();
            Map<String,Long> counts=new LinkedHashMap<>(); for(String type:PUBLIC_EVENT_TYPES)counts.put(type,0L);
            Set<Long> eventActions=new HashSet<>();
            for(PeriodEvent event:events){counts.put(event.getEventType(),counts.get(event.getEventType())+1);if(event.getActionId()!=null)eventActions.add(event.getActionId());}
            boolean questions=current&&period&&request.getPlanId()==null&&access.isQuestionsAllowed();
            String availability=request.getPlanId()!=null?"NOT_INCLUDED_IN_PLAN_SCOPE":access.isQuestionsAllowed()?"AVAILABLE":"NOT_AUTHORIZED";
            List<Question> questionRows=questions?questions():Collections.emptyList();
            budget.checkTime();
            CareExecutionReport report=new CareExecutionReport(patients.get(0),new Scope(request.getPlanId(),request.getPlanId()==null?"ALL_PLANS":"SINGLE_PLAN"),
                    new Metadata(request,asOf,Instant.now()),current?new CurrentSummary(open,help,submitted,confirmed,overdue,supplement):null,
                    actions,attention,period?new ActivitySummary(events.size(),eventActions.size(),counts):null,events,availability,questionRows);
            return new Projection(report,new Manifest(questions,manifest));
        }

        private String planScope() {return request.getPlanId()==null?"":" AND p.id=?";}
        private List<Object> planArgs() {List<Object> args=new ArrayList<>();args.add(request.getPatientId());if(request.getPlanId()!=null)args.add(request.getPlanId());return args;}
        private List<CurrentAction> currentActions() {
            // LEFT joins here are intentional: corrupt relations must fail, not disappear from N.
            String active=" FROM doctor_care_plan p LEFT JOIN care_plan_revision r ON r.id=p.current_revision_id WHERE p.patient_id=? AND p.workflow_version=1 AND p.lifecycle='ACTIVE'"+planScope();
            if(!query("SELECT p.id"+active+" AND (r.id IS NULL OR r.plan_id<>p.id OR r.status<>'PUBLISHED') LIMIT 1",(rs,n)->rs.getLong(1),planArgs()).isEmpty())throw inconsistent();
            String from=" FROM doctor_care_plan p JOIN care_plan_revision r ON r.id=p.current_revision_id AND r.plan_id=p.id AND r.status='PUBLISHED' JOIN care_plan_action a ON a.revision_id=r.id WHERE p.patient_id=? AND p.workflow_version=1 AND p.lifecycle='ACTIVE'"+planScope();
            List<Long> ids=query("SELECT a.id"+from+" ORDER BY p.id,a.ordinal,a.id LIMIT 1001",(rs,n)->rs.getLong(1),planArgs());
            budget.requireRows("CURRENT_ACTIONS",ids.size(),MAX_CURRENT_ACTIONS); if(ids.isEmpty())return Collections.emptyList();
            String selected=" FROM care_plan_action a JOIN doctor_care_plan p ON p.current_revision_id=a.revision_id JOIN care_plan_revision r ON r.id=a.revision_id AND r.plan_id=p.id AND r.status='PUBLISHED' WHERE a.id IN ("+marks(ids.size())+")";
            List<ActionRow> rows=boundedQuery("SELECT a.id,a.plan_id,a.patient_id,a.revision_id,a.ordinal,a.instruction,a.status,a.due_at,a.assigned_user_id,a.review_waiting_since,p.id AS actual_plan,r.revision_no,r.title,r.instructions",selected," ORDER BY p.id,a.ordinal,a.id","a.id",ids,0,(rs,n)->{
                ActionRow a=new ActionRow();a.id=rs.getLong("id");a.plan=rs.getLong("plan_id");a.revision=rs.getLong("revision_id");a.ordinal=rs.getInt("ordinal");
                if(a.plan!=rs.getLong("actual_plan")||request.getPatientId()!=rs.getLong("patient_id"))throw inconsistent();
                a.assigned=rs.getLong("assigned_user_id");a.revisionNo=rs.getInt("revision_no");a.title=text(rs.getString("title"));a.instructions=text(rs.getString("instructions"));a.instruction=text(rs.getString("instruction"));a.status=rs.getString("status");
                a.due=instant(rs,"due_at");a.waiting=instant(rs,"review_waiting_since");
                if(!STATES.contains(a.status)||a.due==null||("SUBMITTED".equals(a.status)&&a.waiting==null))throw inconsistent();
                return a;
            },"a.instruction","r.title","r.instructions");
            Map<Long,Map<Integer,List<Reference>>> revisionRefs=revisionReferences(rows);
            // One grouped ID selection, followed by bounded batch detail reads; never an action detail/history loop.
            List<Long> latestIds=query("SELECT MAX(e.id) AS id FROM care_plan_event e WHERE e.action_id IN ("+marks(ids.size())+") AND e.event_type IN ('RECEIPT_SUBMITTED','RECEIPT_RETURNED','RECEIPT_CONFIRMED','HELP_REQUESTED','FOLLOW_UP_RECORDED') GROUP BY e.action_id,e.event_type",(rs,n)->rs.getLong(1),ids);
            Map<Long,Map<String,EventRow>> latest=new HashMap<>();
            Map<Long,ActionRow> byId=new HashMap<>();for(ActionRow a:rows)byId.put(a.id,a);
            for(EventRow e:eventRows(latestIds,false)) {
                ActionRow a=byId.get(e.action);if(a==null||e.plan!=a.plan||e.revision!=a.revision)throw inconsistent();
                latest.computeIfAbsent(a.id,k->new HashMap<>()).put(e.type,e);
            }
            Map<Long,List<Reference>> eventRefs=eventReferences(latestIds);
            List<CurrentAction> result=new ArrayList<>();
            for(ActionRow a:rows) {
                budget.checkTime();Map<String,EventRow> e=latest.getOrDefault(a.id,Collections.emptyMap());
                long stateId=0;String lastType=null;for(EventRow event:e.values())if(!"FOLLOW_UP_RECORDED".equals(event.type)&&event.id>stateId){stateId=event.id;lastType=event.type;}
                boolean needsSupplement="OPEN".equals(a.status)&&"RECEIPT_RETURNED".equals(lastType);
                boolean overdue=("OPEN".equals(a.status)||"NEEDS_HELP".equals(a.status))&&a.due.isBefore(asOf);
                result.add(new CurrentAction(a.plan,a.revision,a.id,a.assigned,a.revisionNo,a.title,a.instructions,a.instruction,a.status,a.due,a.waiting,
                        overdue,needsSupplement,assigneeAvailable(a.assigned),summary(e.get("RECEIPT_SUBMITTED"),eventRefs),summary(e.get("RECEIPT_RETURNED"),eventRefs),summary(e.get("RECEIPT_CONFIRMED"),eventRefs),summary(e.get("HELP_REQUESTED"),eventRefs),summary(e.get("FOLLOW_UP_RECORDED"),eventRefs),
                        evidence(revisionRefs.getOrDefault(a.revision,Collections.emptyMap()).getOrDefault(a.ordinal,Collections.emptyList()))));
            }
            return result;
        }

        private boolean assigneeAvailable(long id) {
            if(!assignees.containsKey(id)) {
                boolean allowed=true;try{auth.requireRecord(id,request.getPatientId());}catch(CarePlanException ex){if(ex.getStatus()!=403)throw ex;allowed=false;}
                assignees.put(id,allowed);
            }
            return assignees.get(id);
        }

        private Map<Long,Map<Integer,List<Reference>>> revisionReferences(List<ActionRow> actions) {
            Set<Long> revisions=new LinkedHashSet<>();for(ActionRow a:actions)revisions.add(a.revision);
            List<Long> ids=new ArrayList<>(revisions);String from=" FROM care_plan_revision r WHERE r.status='PUBLISHED' AND r.id IN ("+marks(ids.size())+")";
            Map<Long,Map<Integer,List<Reference>>> result=new HashMap<>();
            boundedQuery("SELECT r.id,r.draft_json",from,"","r.id",ids,0,(rs,n)->{
                JsonNode root=json(rs.getString("draft_json"));JsonNode values=root.get("actions");Map<Integer,List<Reference>> byOrdinal=new HashMap<>();
                if(values!=null) {
                    if(!values.isArray()||values.size()>50)throw inconsistent();
                    for(JsonNode value:values) {JsonNode ordinal=value.get("ordinal");if(ordinal==null||!ordinal.canConvertToInt()||ordinal.intValue()<1||ordinal.intValue()>50||byOrdinal.containsKey(ordinal.intValue()))throw inconsistent();byOrdinal.put(ordinal.intValue(),references(value.get("evidence")));}
                }
                result.put(rs.getLong("id"),byOrdinal);return 0;
            },"r.draft_json");return result;
        }

        private List<PeriodEvent> periodEvents() {
            String scope=" FROM care_plan_event e JOIN doctor_care_plan p ON p.id=e.plan_id LEFT JOIN care_plan_revision r ON r.id=e.revision_id LEFT JOIN care_plan_action a ON a.id=e.action_id WHERE e.patient_id=? AND p.workflow_version=1"+planScope()+
                    " AND e.recorded_at>=? AND e.recorded_at<? AND e.event_type IN ("+PUBLIC_SQL+")";
            List<Object> args=planArgs();args.add(request.getRangeStartAt());args.add(request.getRangeEndExclusiveAt());
            // Published-only content, but diagnose broken public relations before an INNER JOIN can hide them.
            if(!query("SELECT e.id"+scope+" AND (p.patient_id<>e.patient_id OR r.id IS NULL OR r.plan_id<>p.id OR (r.status='PUBLISHED' AND e.action_id IS NOT NULL AND (a.id IS NULL OR a.plan_id<>p.id OR a.revision_id<>r.id OR a.patient_id<>e.patient_id))) LIMIT 1",(rs,n)->rs.getLong(1),args).isEmpty())throw inconsistent();
            List<Long> ids=query("SELECT e.id"+scope+" AND r.status='PUBLISHED' ORDER BY e.recorded_at,e.id LIMIT 5001",(rs,n)->rs.getLong(1),args);
            budget.requireRows("PERIOD_EVENTS",ids.size(),MAX_PERIOD_EVENTS); if(ids.isEmpty())return Collections.emptyList();
            List<EventRow> rows=eventRows(ids,true);Map<Long,List<Reference>> refs=eventReferences(ids);
            List<PeriodEvent> result=new ArrayList<>();
            for(EventRow e:rows) {
                String history=historicalStatus(e);
                result.add(new PeriodEvent(summary(e,refs),e.plan,e.revision,e.action,e.revisionNo,e.title,e.instructions,e.instruction,history,e.lifecycle,e.current));
            }
            result.sort(Comparator.comparing(PeriodEvent::getRecordedAt).thenComparingLong(PeriodEvent::getEventId));return result;
        }

        private List<EventRow> eventRows(List<Long> ids,boolean context) {
            if(ids.isEmpty())return Collections.emptyList();
            String from=" FROM care_plan_event e JOIN doctor_care_plan p ON p.id=e.plan_id JOIN care_plan_revision r ON r.id=e.revision_id AND r.plan_id=p.id AND r.status='PUBLISHED' LEFT JOIN care_plan_action a ON a.id=e.action_id WHERE e.id IN ("+marks(ids.size())+")";
            List<EventRow> result=boundedQuery("SELECT e.id,e.patient_id,e.plan_id,e.revision_id,e.action_id,e.actor_id,e.actor_name,e.actor_role,e.actor_relation,e.entry_mode,e.event_type,e.note,e.occurred_at,e.recorded_at,e.payload_json,r.revision_no,r.title,r.instructions,a.instruction,a.plan_id AS action_plan,a.revision_id AS action_revision,a.patient_id AS action_patient,p.patient_id AS plan_patient,p.lifecycle,p.current_revision_id",from,"","e.id",ids,0,(rs,n)->{
                EventRow e=new EventRow();e.id=rs.getLong("id");e.plan=rs.getLong("plan_id");e.revision=rs.getLong("revision_id");e.action=CarePlanData.nullableId(rs,"action_id");e.type=rs.getString("event_type");
                if(request.getPatientId()!=rs.getLong("patient_id")||request.getPatientId()!=rs.getLong("plan_patient")||!PUBLIC_EVENT_TYPES.contains(e.type))throw inconsistent();
                if(e.action!=null&&(rs.getLong("action_plan")!=e.plan||rs.getLong("action_revision")!=e.revision||rs.getLong("action_patient")!=request.getPatientId()))throw inconsistent();
                if(ACTION_EVENTS.contains(e.type)&&e.action==null)throw inconsistent();
                e.actor=rs.getLong("actor_id");e.actorName=text(rs.getString("actor_name"));e.actorRole=text(rs.getString("actor_role"));e.actorRelation=text(rs.getString("actor_relation"));e.mode=text(rs.getString("entry_mode"));e.note=text(rs.getString("note"));e.recorded=instant(rs,"recorded_at");e.occurred=instant(rs,"occurred_at");
                JsonNode payload=json(rs.getString("payload_json"));e.kind="FOLLOW_UP_RECORDED".equals(e.type)?text(jsonText(payload,"kind")):null;e.historicalStatus=jsonText(payload,"actionStatus");
                if(e.historicalStatus!=null&&!STATES.contains(e.historicalStatus))throw inconsistent();
                if("FOLLOW_UP_RECORDED".equals(e.type)&&!Arrays.asList("CONTACTED","AWAITING_INFORMATION","DOCTOR_NOTIFIED").contains(e.kind))throw inconsistent();
                e.revisionNo=rs.getInt("revision_no");e.title=context?text(rs.getString("title")):null;e.instructions=context?text(rs.getString("instructions")):null;e.instruction=context?text(rs.getString("instruction")):null;e.lifecycle=rs.getString("lifecycle");e.current=e.revision==rs.getLong("current_revision_id");
                if(e.recorded==null||!Arrays.asList("ACTIVE","COMPLETED","CANCELLED").contains(e.lifecycle))throw inconsistent();return e;
            },"e.actor_name","e.actor_role","e.actor_relation","e.entry_mode","e.note","e.payload_json","r.title","r.instructions","a.instruction");
            if(result.size()!=ids.size())throw inconsistent();return result;
        }

        private String historicalStatus(EventRow e) {
            if(e.action==null)return null;
            String semantic="RECEIPT_SUBMITTED".equals(e.type)?"SUBMITTED":"RECEIPT_RETURNED".equals(e.type)?"OPEN":"RECEIPT_CONFIRMED".equals(e.type)?"CONFIRMED":"HELP_REQUESTED".equals(e.type)?"NEEDS_HELP":null;
            if(semantic!=null&&e.historicalStatus!=null&&!semantic.equals(e.historicalStatus))throw inconsistent();
            return semantic==null?e.historicalStatus:semantic;
        }

        private Map<Long,List<Reference>> eventReferences(List<Long> ids) {
            Map<Long,List<Reference>> result=new HashMap<>();if(ids.isEmpty())return result;
            // Existing receipt contract is at most five references. LIMIT protects corrupt legacy rows too.
            List<Object> args=new ArrayList<>(ids);args.add(ids.size()*5+1);
            query("SELECT event_id,source_type,source_id FROM care_plan_evidence WHERE event_id IN ("+marks(ids.size())+") ORDER BY event_id,id LIMIT ?",(rs,n)->{
                List<Reference> refs=result.computeIfAbsent(rs.getLong("event_id"),k->new ArrayList<>());
                if(refs.size()>=5)throw inconsistent();refs.add(new Reference(rs.getString("source_type"),rs.getLong("source_id")));return 0;
            },args);return result;
        }

        private EventSummary summary(EventRow e,Map<Long,List<Reference>> refs) {
            if(e==null)return null;historicalStatus(e);
            return new EventSummary(e.id,e.actor,e.type,e.actorName,e.actorRole,e.actorRelation,e.mode,e.note,e.kind,e.recorded,e.occurred,evidence(refs.getOrDefault(e.id,Collections.emptyList())));
        }

        private List<Evidence> evidence(List<Reference> refs) {
            List<Evidence> result=new ArrayList<>();
            for(Reference ref:refs) {
                budget.checkTime();
                if(!auth.canReadEvidence(actor,request.getPatientId(),ref.type,ref.id)){result.add(new Evidence(true,null,null,null,null));continue;}
                String table="MEASUREMENT".equals(ref.type)?"health_measurement":"medical_record";
                String column="MEASUREMENT".equals(ref.type)?"metric_type":"record_type";
                List<String> titles=query("SELECT "+column+" FROM "+table+" WHERE id=? AND patient_id=?",(rs,n)->rs.getString(1),ref.id,request.getPatientId());
                if(titles.size()!=1){result.add(new Evidence(true,null,null,null,null));continue;}
                String path="MEASUREMENT".equals(ref.type)?"/care-journey?tab=measurements&patientId="+request.getPatientId()+"&measurementId="+ref.id:"/medical-record?tab=list&patientId="+request.getPatientId()+"&recordId="+ref.id;
                result.add(new Evidence(false,ref.type,ref.id,text(titles.get(0)),path));manifest.add(new EvidenceKey(ref.type,ref.id));
            }
            return result;
        }

        private List<Question> questions() {
            List<Long> ids=query("SELECT id FROM care_item WHERE patient_id=? AND kind='QUESTION' ORDER BY id LIMIT 201",(rs,n)->rs.getLong(1),request.getPatientId());
            budget.requireRows("QUESTIONS",ids.size(),MAX_QUESTIONS);if(ids.isEmpty())return Collections.emptyList();
            String from=" FROM care_item q WHERE q.patient_id=? AND q.kind='QUESTION' AND q.id IN ("+marks(ids.size())+")";
            List<Object> args=new ArrayList<>();args.add(request.getPatientId());args.addAll(ids);
            return boundedQuery("SELECT q.id,q.title,q.status,q.actor_id,q.actor_name,q.created_at,q.updated_at,q.event_at,q.data_json",from," ORDER BY q.id","q.id",args,1,(rs,n)->{
                JsonNode details=json(rs.getString("data_json"));
                return new Question(rs.getLong("id"),text(rs.getString("title")),text(rs.getString("status")),text(jsonText(details,"description")),text(jsonText(details,"answer")),text(jsonText(details,"followUp")),text(rs.getString("actor_name")),CarePlanData.nullableId(rs,"actor_id"),local(rs,"created_at"),local(rs,"updated_at"),local(rs,"event_at"));
            },"q.title","q.status","q.actor_name","q.data_json");
        }

        /**
         * Bound the actual driver's result allocation, not just retained DTO strings. Scalar lengths
         * precede any body read in the same RR snapshot; each cell is capped and batches total <=8 MiB
         * (a single multi-column row can exceed that sum, but every cell remains independently bounded).
         * Raw legacy JSON is released after its row is projected; only output fields consume the budget.
         */
        private <T> List<T> boundedQuery(String select,String from,String order,String idColumn,List<?> args,int prefix,RowMapper<T> mapper,String... columns) {
            StringJoiner lengths=new StringJoiner(",");for(int i=0;i<columns.length;i++)lengths.add("COALESCE("+storageBytes(columns[i])+",0) AS bytes"+i);
            List<CellSize> sizes=query("SELECT "+idColumn+" AS cell_id,"+lengths+from+order,(rs,n)->{
                long total=0;for(int i=0;i<columns.length;i++){long length=rs.getLong("bytes"+i);if(length>MAX_SOURCE_TEXT_BYTES)throw CareExecutionReportException.limitExceeded("SOURCE_TEXT_BYTES",MAX_SOURCE_TEXT_BYTES);total+=length;}
                return new CellSize(rs.getLong("cell_id"),total);
            },args);
            List<T> result=new ArrayList<>();List<Long> batch=new ArrayList<>();long bytes=0;
            for(CellSize row:sizes){
                if(!batch.isEmpty()&&bytes+row.bytes>MAX_SOURCE_TEXT_BYTES){result.addAll(bodyBatch(select,from,order,args,prefix,batch,mapper));batch.clear();bytes=0;}
                batch.add(row.id);bytes+=row.bytes;
            }
            if(!batch.isEmpty())result.addAll(bodyBatch(select,from,order,args,prefix,batch,mapper));
            return result;
        }
        private String storageBytes(String column) {
            // H2 1.4 OCTET_LENGTH(text) counts UTF-16 storage; production MySQL utf8mb4 counts UTF-8.
            return "CURRENT_TIMESTAMP".equals(dbNow)?"LENGTH(STRINGTOUTF8("+column+"))":"OCTET_LENGTH("+column+")";
        }
        private <T> List<T> bodyBatch(String select,String from,String order,List<?> args,int prefix,List<Long> ids,RowMapper<T> mapper) {
            String bounded=from.replace("IN ("+marks(args.size()-prefix)+")","IN ("+marks(ids.size())+")");
            List<Object> values=new ArrayList<>(args.subList(0,prefix));values.addAll(ids);
            return query(select+bounded+order,mapper,values);
        }
        private String text(String value){budget.addSourceText(value);return value;}
        private <T>List<T> query(String sql,RowMapper<T> mapper,Object... args){return query(sql,mapper,Arrays.asList(args));}
        private <T>List<T> query(String sql,RowMapper<T> mapper,List<?> args) {
            budget.checkTime();List<T> result=jdbc.query(c->{PreparedStatement ps=c.prepareStatement(sql);for(int i=0;i<args.size();i++){Object arg=args.get(i);if(arg instanceof Instant)CarePlanData.time(ps,i+1,(Instant)arg);else ps.setObject(i+1,arg);}return ps;},(rs,n)->{budget.checkTime();return mapper.mapRow(rs,n);});budget.checkTime();return result;
        }
    }

    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static Instant instant(ResultSet rs,String column)throws SQLException {Timestamp timestamp=rs.getTimestamp(column,CarePlanData.utc());return timestamp==null?null:timestamp.toInstant();}
    private static String local(ResultSet rs,String column)throws SQLException {String value=rs.getString(column);return value;}
    private static JsonNode json(String raw) {if(raw==null)return JSON.createObjectNode();try{JsonNode node=JSON.readTree(raw);if(node==null||!node.isObject())throw inconsistent();return node;}catch(java.io.IOException ex){throw inconsistent();}}
    private static String jsonText(JsonNode node,String field){JsonNode value=node.get(field);if(value==null||value.isNull())return null;if(!value.isTextual())throw inconsistent();return value.textValue();}
    private static List<Reference> references(JsonNode values) {
        if(values==null||values.isNull())return Collections.emptyList();if(!values.isArray()||values.size()>5)throw inconsistent();List<Reference> result=new ArrayList<>();
        for(JsonNode value:values){String type=jsonText(value,"sourceType");JsonNode id=value.get("sourceId");if(type==null||id==null||!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0)throw inconsistent();result.add(new Reference(type,id.longValue()));}return result;
    }
    private static CareExecutionReportException inconsistent(){return new CareExecutionReportException(CareExecutionReportException.Code.REPORT_DATA_INCONSISTENT);}
    private static final class CellSize {final long id,bytes;CellSize(long id,long bytes){this.id=id;this.bytes=bytes;}}
    private static final class Reference {final String type;final long id;Reference(String type,long id){this.type=type;this.id=id;}}
    private static final class ActionRow {long id,plan,revision,assigned;int ordinal,revisionNo;String title,instructions,instruction,status;Instant due,waiting;}
    private static final class EventRow {long id,plan,revision,actor;Long action;int revisionNo;String type,actorName,actorRole,actorRelation,mode,note,kind,historicalStatus,title,instructions,instruction,lifecycle;Instant recorded,occurred;boolean current;}
    public static final class Projection {
        private final CareExecutionReport report;
        private final Manifest manifest;
        public Projection(CareExecutionReport report,Manifest manifest){this.report=Objects.requireNonNull(report);this.manifest=Objects.requireNonNull(manifest);}
        public CareExecutionReport getReport(){return report;}
        public Manifest getManifest(){return manifest;}
    }
}
