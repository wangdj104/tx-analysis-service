package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.stockworkflow.StockWorkflowAuthorizationService.denied;
@Repository public class StockWorkflowRepository  {
    final JdbcTemplate jdbc;
    final HouseholdAuthorityLocks locks;
    final HouseholdWorkflowSchema schema;
    final StockWorkflowAuthorizationService auth;
    public StockWorkflowRepository(JdbcTemplate jdbc,HouseholdAuthorityLocks locks,HouseholdWorkflowSchema schema,StockWorkflowAuthorizationService auth)  {
        this.jdbc=jdbc;
        this.locks=locks;
        this.schema=schema;
        this.auth=auth;
    }
    public Map<String,Object> stock(long id)  {
        return one("SELECT * FROM medication_stock WHERE id=?",id);
    }
    /** Current rule is either the immutable opening snapshot or one direct, authenticated event reference.
     * The latest work event is an indexed endpoint; no type-filtered history scan or pointer chain is used. */
    public Map<String,Object> work(long id) {
        Map<String,Object> w=one("SELECT * FROM stock_work WHERE id=?",id);
        if(w==null)return null;
        Map<String,Object> stock=stock(id(w.get("stock_id")));
        Map<String,Object> anchor=one("SELECT * FROM stock_event WHERE work_id=? ORDER BY id DESC LIMIT 1",id);
        validateRuleEvent(anchor,stock,w);
        Map<String,Object> rule=anchor;
        if(!"CHANGE_RULE".equals(anchor.get("type"))) {
            Map<String,Object> evidence=object((String)anchor.get("evidence_json"));
            if(!evidence.containsKey("ruleEventId")||!evidence.containsKey("ruleVersion")||!evidence.containsKey("ruleHash"))throw conflict("RULE_EVIDENCE_INVALID");
            if(evidence.get("ruleEventId")==null) {
                if(evidence.get("ruleVersion")!=null||evidence.get("ruleHash")!=null)throw conflict("RULE_EVIDENCE_INVALID");
                rule=null;
            } else {
                rule=one("SELECT * FROM stock_event WHERE id=?",evidence.get("ruleEventId"));
                validateRuleEvent(rule,stock,w);
                if(!"CHANGE_RULE".equals(rule.get("type"))||id(rule.get("id"))>=id(anchor.get("id"))||id(rule.get("aggregate_version"))>=id(anchor.get("aggregate_version"))||!sameDecimal(rule.get("aggregate_version"),evidence.get("ruleVersion"))||!Objects.equals(rule.get("evidence_hash"),evidence.get("ruleHash")))throw conflict("RULE_EVIDENCE_INVALID");
            }
        }
        boolean current=Objects.equals(stock.get("current_work_id"),w.get("id"));
        w.put("current_rule_anchor_event_id",anchor.get("id"));
        if(rule==null) {
            if(current&&(!sameDecimal(stock.get("warning_quantity"),w.get("warning_quantity"))||!sameDecimal(stock.get("warning_days"),w.get("warning_days"))))throw conflict("RULE_EVIDENCE_INVALID");
            return w;
        }
        Map<String,Object> evidence=object((String)rule.get("evidence_json"));
        if(!(evidence.get("beforeRule") instanceof Map)||!(evidence.get("afterRule") instanceof Map))throw conflict("RULE_EVIDENCE_INVALID");
        Map<String,Object> after=object(json(evidence.get("afterRule")));
        try {
            if(!after.keySet().equals(new HashSet<>(Arrays.asList("warningQuantity","warningDays","dailyUseQuantity","unit"))))throw new IllegalArgumentException();
            java.math.BigDecimal quantity=StockWorkflowContracts.decimal(after.get("warningQuantity"),true);
            long days=org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.integer(after.get("warningDays"),"warningDays",0,36500);
            java.math.BigDecimal daily=after.get("dailyUseQuantity")==null?null:StockWorkflowContracts.decimal(after.get("dailyUseQuantity"),false);
            if(!Objects.equals(after.get("unit"),w.get("opening_unit"))||current&&(!sameDecimal(stock.get("warning_quantity"),quantity)||((Number)stock.get("warning_days")).longValue()!=days))throw new IllegalArgumentException();
            w.put("daily_use_quantity",daily);
            w.put("current_rule_event_id",rule.get("id"));w.put("current_rule_version",rule.get("aggregate_version"));w.put("current_rule_hash",rule.get("evidence_hash"));
            return w;
        } catch(RuntimeException invalid) {throw conflict("RULE_EVIDENCE_INVALID");}
    }
    private static void validateRuleEvent(Map<String,Object> event,Map<String,Object> stock,Map<String,Object> work) {
        if(event==null||stock==null||id(event.get("stock_id"))!=id(stock.get("id"))||id(event.get("work_id"))!=id(work.get("id"))||id(event.get("patient_id"))!=id(work.get("patient_id"))||id(event.get("cycle"))!=id(work.get("cycle"))||id(event.get("aggregate_version"))>id(stock.get("lock_version"))||!hash((String)event.get("evidence_json")).equals(event.get("evidence_hash")))throw conflict("RULE_EVIDENCE_INVALID");
    }
    private static boolean sameDecimal(Object a,Object b) {
        return a==null?b==null:b!=null&&new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;
    }
    public Map<String,Object> count(long id)  {
        return one("SELECT * FROM stock_count WHERE id=?",id);
    }
    public HouseholdAuthorityLocks.LockedAuthority lockCommand(long actor,long patient,long stock,StockWorkflowContracts.StockCommand c)  {
        return admit(actor,patient,stock,c==null?null:c.assignedUserId,c==null?null:c.intakeId,c==null?null:c.actionId,c==null?null:c.countId,c==null?null:c.commandKey,null,null);
    }
    public HouseholdAuthorityLocks.LockedAuthority lockConsumption(long actor,long patient,long stock,StockWorkflowContracts.RecordedIntakeAction a,Long original,Long count)  {
        Object prepared=TransactionSynchronizationManager.getResource(preparedKey);
        if(prepared!=null)return ((PreparedConsumption)prepared).consume(actor,patient,stock,a,original,count);
        return admit(actor,patient,stock,a.actorId,a.intakeId,a.actionId,count,null,original,null);
    }
    public HouseholdAuthorityLocks.LockedAuthority lockNotification(long actor,long patient,long stock,long event,long notification,Long recipient)  {
        return admit(actor,patient,stock,recipient,null,null,null,null,event,notification);
    }
    private HouseholdAuthorityLocks.LockedAuthority admit(long actor,long patient,long stockId,Long extraUser,Long intakeId,Long actionId,Long countId,String commandKey,Long extra,Long notification)  {
        ConsumptionSelection selection=discover(actor,patient,stockId,extraUser,intakeId,actionId,countId,commandKey,extra,notification);
        LinkedHashMap<String,SortedSet<Long>> graph=selection.rows;SortedSet<Long> users=selection.users;Map<String,Object> replay=selection.replay;
        Map<String,Object> s;
        int total=0;
        for(Set<Long> ids:graph.values())total+=ids.size();
        if(total>50||users.size()>50)throw conflict("ADMISSION_LIMIT");
        HouseholdAuthorityLocks.LockedAuthority token=locks.lockPatientAndSubjects(patient,users);
        for(Map.Entry<String,SortedSet<Long>> entry:graph.entrySet())for(long target:entry.getValue())if(one("SELECT id FROM "+entry.getKey()+" WHERE id=? FOR UPDATE",target)==null)throw denied();
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();
        for(Map.Entry<String,SortedSet<Long>> entry:graph.entrySet())if(!entry.getValue().isEmpty()&&!entry.getKey().equals("stock_command"))selected.put(entry.getKey(),entry.getValue());
        schema.requireSelectedRows(token,patient,actor,selected);
        s=stock(stockId);
        if(s==null||id(s.get("patient_id"))!=patient)throw denied();
        auth.requireWrite(actor,patient);
        if(replay!=null)  {
            Map<String,Object> event=one("SELECT * FROM stock_event WHERE id=?",replay.get("eventId"));
            if(event==null||id(event.get("stock_id"))!=stockId||id(event.get("work_id"))!=id(replay.get("workId"))||id(event.get("aggregate_version"))!=id(replay.get("version"))||id(event.get("cycle"))!=id(replay.get("cycle")))throw conflict("RECEIPT_INVALID");
        }
        return token;
    }
    private ConsumptionSelection discover(long actor,long patient,long stockId,Long extraUser,Long intakeId,Long actionId,Long countId,String commandKey,Long extra,Long notification)  {
        mutationTransaction(jdbc);
        schema.inspectStorageMetadata().requireStructurallyReady();
        if(one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw denied();
        Map<String,Object> s=stock(stockId);
        if(s==null||id(s.get("patient_id"))!=patient)throw denied();
        LinkedHashMap<String,SortedSet<Long>> graph=new LinkedHashMap<>();
        for(String t:Arrays.asList("medication","medication_execution","medication_reconciliation","medication_reconciliation_revision","medication_intake","care_intake_action","medication_stock","stock_work","stock_count","stock_intake_resolution","medication_execution_event","stock_event","stock_command","stock_notification"))graph.put(t,new TreeSet<>());
        add(graph,"medication",s.get("medication_id"));
        add(graph,"medication_stock",stockId);
        add(graph,"stock_work",s.get("current_work_id"));
        add(graph,"stock_count",countId);
        add(graph,"medication_intake",intakeId);
        add(graph,"care_intake_action",actionId);
        if(extra!=null)add(graph,notification==null?"care_intake_action":"stock_event",extra);
        add(graph,"stock_notification",notification);
        SortedSet<Long> users=new TreeSet<>();
        users.add(actor);
        if(extraUser!=null)users.add(extraUser);
        Map<String,Object> w=s.get("current_work_id")==null?null:work(id(s.get("current_work_id")));
        if(w!=null)  {
            add(graph,"stock_event",w.get("current_rule_event_id"));
            add(graph,"stock_event",w.get("current_rule_anchor_event_id"));
            user(users,w.get("assigned_user_id"));
            user(users,w.get("created_by"));
        }
        Map<String,Object> replay=null;
        if(commandKey!=null)  {
            Map<String,Object> row=one("SELECT * FROM stock_command WHERE actor_id=? AND command_key=?",actor,commandKey);
            if(row!=null)  {
                replay=object((String)row.get("result_json"));
                StockWorkflowContracts.requireMinimalReceipt(replay);
                if(id(row.get("stock_id"))!=stockId||id(replay.get("stockId"))!=stockId)throw conflict("RECEIPT_INVALID");
                add(graph,"stock_command",row.get("id"));
                add(graph,"stock_event",replay.get("eventId"));
                add(graph,"stock_work",replay.get("workId"));
                add(graph,"stock_count",replay.get("countId"));
                add(graph,"stock_intake_resolution",replay.get("resolutionId"));
            }
        }
        if(intakeId!=null&&actionId!=null) {
            Map<String,Object> intake=one("SELECT latest_action_id FROM medication_intake WHERE id=? AND patient_id=? AND medication_id=?",intakeId,patient,s.get("medication_id"));
            if(intake==null)throw denied();
            // A successful immutable receipt needs current authority/identity and its explicit evidence,
            // not an obsolete walk from the replayed action toward newer descendant anchors.
            Map<String,Object> successful=one("SELECT * FROM stock_event WHERE settled_action_id=?",actionId);
            if(successful!=null) {
                validateSettlement(successful,stockId,intakeId,patient);
                if(!settled(successful))throw conflict("SETTLEMENT_EVIDENCE_INVALID");
                add(graph,"stock_event",successful.get("id"));add(graph,"stock_work",successful.get("work_id"));
            }
            SortedSet<Long> anchors=new TreeSet<>();
            if(intake.get("latest_action_id")!=null)anchors.add(id(intake.get("latest_action_id")));
            Map<String,Object> resolution=one("SELECT * FROM stock_intake_resolution WHERE intake_id=?",intakeId);
            if(resolution!=null) {add(graph,"stock_intake_resolution",resolution.get("id"));anchors.add(id(resolution.get("current_action_id")));}
            Map<String,Object> receipt=settlementAnchor(stockId,intakeId,patient),settled=lastSettled(stockId,intakeId,patient);
            if(receipt!=null) {add(graph,"stock_event",receipt.get("id"));add(graph,"stock_work",receipt.get("work_id"));}
            if(settled!=null) {add(graph,"stock_event",settled.get("id"));add(graph,"stock_work",settled.get("work_id"));anchors.add(id(settled.get("settled_action_id")));}
            for(long anchor:anchors)add(graph,"care_intake_action",anchor);
            Set<Long> seen=new HashSet<>();Long cursor=actionId;
            while(successful==null&&cursor!=null) {
                if(!seen.add(cursor))throw conflict("ACTION_CHAIN_INVALID");
                Map<String,Object> row=one("SELECT patient_id,intake_id,original_action_id FROM care_intake_action WHERE id=?",cursor);
                if(row==null||id(row.get("patient_id"))!=patient||id(row.get("intake_id"))!=intakeId)throw denied();
                add(graph,"care_intake_action",cursor);anchors.remove(cursor);
                int size=0;for(Set<Long> ids:graph.values())size+=ids.size();if(size>50)throw conflict("ADMISSION_LIMIT");
                if(anchors.isEmpty())break;
                cursor=row.get("original_action_id")==null?null:id(row.get("original_action_id"));
            }
        }
        for(long action:new ArrayList<>(graph.get("care_intake_action"))) {
            Map<String,Object> row=one("SELECT original_action_id FROM care_intake_action WHERE id=?",action);
            if(row!=null)add(graph,"care_intake_action",row.get("original_action_id"));
        }
        for(long a:new ArrayList<>(graph.get("care_intake_action")))  {
            Map<String,Object> row=one("SELECT * FROM care_intake_action WHERE id=?",a);
            if(row==null||id(row.get("patient_id"))!=patient)throw denied();
            add(graph,"medication_intake",row.get("intake_id"));
            add(graph,"medication_execution",row.get("execution_id"));
            add(graph,"medication_execution_event",row.get("execution_event_id"));
            user(users,row.get("actor_id"));
            Map<String,Object> settled=one("SELECT id FROM stock_event WHERE settled_action_id=? AND stock_id=? AND patient_id=?",a,stockId,patient);
            if(settled!=null)add(graph,"stock_event",settled.get("id"));
        }
        for(long i:graph.get("medication_intake"))  {
            Map<String,Object> row=one("SELECT * FROM medication_intake WHERE id=?",i);
            if(row==null||id(row.get("patient_id"))!=patient||id(row.get("medication_id"))!=id(s.get("medication_id")))throw denied();
            add(graph,"medication_execution",row.get("execution_id"));
            add(graph,"medication_reconciliation",row.get("reconciliation_id"));
        }
        for(long e:graph.get("medication_execution"))  {
            Map<String,Object> row=one("SELECT * FROM medication_execution WHERE id=?",e);
            if(row==null||id(row.get("patient_id"))!=patient||id(row.get("medication_id"))!=id(s.get("medication_id")))throw denied();
        }
        for(long e:graph.get("medication_execution_event"))  {
            Map<String,Object> row=one("SELECT * FROM medication_execution_event WHERE id=?",e);
            if(row==null||id(row.get("patient_id"))!=patient||!graph.get("medication_execution").contains(id(row.get("execution_id"))))throw denied();
            add(graph,"medication_reconciliation",row.get("reconciliation_id"));
            add(graph,"medication_reconciliation_revision",row.get("revision_id"));
            user(users,row.get("actor_id"));
        }
        return new ConsumptionSelection(graph,users,replay);
    }

    private static final class ConsumptionSelection {
        final LinkedHashMap<String,SortedSet<Long>> rows;final SortedSet<Long> users;final Map<String,Object> replay;
        ConsumptionSelection(LinkedHashMap<String,SortedSet<Long>> rows,SortedSet<Long> users,Map<String,Object> replay){this.rows=rows;this.users=users;this.replay=replay;}
    }
    private final Object preparedKey=new Object();
    /** A server-owned, one-use continuation of the caller's single merged admission. Never deserialized. */
    public class PreparedConsumption {
        private final long actor,patient,stock,intake;private final Long original,count;
        private final Connection connection;private final Thread thread=Thread.currentThread();private final ConsumptionSelection selection;
        private final long deadline;
        private boolean live=true,consumed=false;private Long insertedAction,insertedEvent;private HouseholdAuthorityLocks.LockedAuthority token;
        private PreparedConsumption(long actor,long patient,long stock,long intake,Long original,Long count,ConsumptionSelection selection,long deadline){
            this.deadline=deadline;this.actor=actor;this.patient=patient;this.stock=stock;this.intake=intake;this.original=original;this.count=count;this.selection=selection;
            connection=DataSourceUtils.getConnection(jdbc.getDataSource());DataSourceUtils.releaseConnection(connection,jdbc.getDataSource());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){invalidate();}@Override public void suspend(){invalidate();}});
        }
        private void invalidate(){live=false;if(TransactionSynchronizationManager.getResource(preparedKey)==this)TransactionSynchronizationManager.unbindResource(preparedKey);}
        private void current(){mutationTransaction(jdbc);Connection c=DataSourceUtils.getConnection(jdbc.getDataSource());try{if(!live||consumed||thread!=Thread.currentThread()||connection!=c||System.nanoTime()>deadline)throw conflict("CONSUMPTION_CONTEXT_CHANGED");}finally{DataSourceUtils.releaseConnection(c,jdbc.getDataSource());}}
        public Map<String,Collection<Long>> selectedRows(){current();Map<String,Collection<Long>> copy=new LinkedHashMap<>();for(Map.Entry<String,SortedSet<Long>> e:selection.rows.entrySet())if(!e.getValue().isEmpty())copy.put(e.getKey(),Collections.unmodifiableSortedSet(new TreeSet<>(e.getValue())));return Collections.unmodifiableMap(copy);}
        public Set<Long> subjects(){current();return Collections.unmodifiableSortedSet(new TreeSet<>(selection.users));}
        /** Parent/identity waits may not silently introduce any old relation. */
        public void check(){current();}
        public void bind(HouseholdAuthorityLocks.LockedAuthority token){current();locks.requireLive(token,patient,actor);if(this.token!=null||token==null||token.getPatientId()!=patient||!token.getUserIds().containsAll(selection.users)||TransactionSynchronizationManager.hasResource(preparedKey))throw conflict("CONSUMPTION_CONTEXT_CHANGED");
            ConsumptionSelection again=discover(actor,patient,stock,actor,intake,original,count,null,null,null);if(!sameSelection(selection,again))throw conflict("CONSUMPTION_CONTEXT_CHANGED");this.token=token;TransactionSynchronizationManager.bindResource(preparedKey,this);
        }
        private void register(String table,long id){current();if(token==null||TransactionSynchronizationManager.getResource(preparedKey)!=this)throw conflict("CONSUMPTION_CONTEXT_CHANGED");if("care_intake_action".equals(table)){if(insertedAction!=null)throw conflict("CONSUMPTION_CONTEXT_CHANGED");insertedAction=id;}else if("medication_execution_event".equals(table)){if(insertedEvent!=null)throw conflict("CONSUMPTION_CONTEXT_CHANGED");insertedEvent=id;}}
        private HouseholdAuthorityLocks.LockedAuthority consume(long actor,long patient,long stock,StockWorkflowContracts.RecordedIntakeAction action,Long original,Long count){current();if(this.actor!=actor||this.patient!=patient||this.stock!=stock||this.intake!=action.intakeId||!Objects.equals(this.original,original)||!Objects.equals(this.count,count)||!Objects.equals(insertedAction,action.actionId)||!Objects.equals(insertedEvent,action.executionEventId)||action.actorId!=actor)throw conflict("CONSUMPTION_CONTEXT_CHANGED");locks.requireLive(token,patient,actor);auth.requireWrite(actor,patient);Map<String,Object> i=one("SELECT patient_id,medication_id FROM medication_intake WHERE id=?",intake),s=stock(stock);if(i==null||s==null||id(i.get("patient_id"))!=patient||id(s.get("patient_id"))!=patient||!Objects.equals(i.get("medication_id"),s.get("medication_id")))throw denied();consumed=true;TransactionSynchronizationManager.unbindResource(preparedKey);return token;}
    }
    private static boolean sameSelection(ConsumptionSelection a,ConsumptionSelection b){return a.rows.equals(b.rows)&&a.users.equals(b.users);}
    public PreparedConsumption prepareConsumption(long actor,long patient,long stock,long intake,Long original,Long count){
        mutationTransaction(jdbc);if(TransactionSynchronizationManager.hasResource(preparedKey))throw conflict("CONSUMPTION_CONTEXT_CHANGED");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        return new PreparedConsumption(actor,patient,stock,intake,original,count,discover(actor,patient,stock,actor,intake,original,count,null,null,null),deadline);
    }
    /** Only the medication repository can construct this same-transaction INSERT receipt. */
    public void registerPreparedAction(PreparedConsumption prepared,org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository.InsertedAction action){prepared.current();action.requireCurrent(jdbc);if(prepared.token==null)throw conflict("CONSUMPTION_CONTEXT_CHANGED");prepared.register("care_intake_action",action.id());}
    public void registerPreparedEvent(PreparedConsumption prepared,long action,long event){prepared.current();if(!Objects.equals(prepared.insertedAction,action)||prepared.insertedEvent!=null)throw conflict("CONSUMPTION_CONTEXT_CHANGED");Map<String,Object> row=one("SELECT patient_id,intake_id,action_id,actor_id FROM medication_execution_event WHERE id=?",event);if(row==null||id(row.get("patient_id"))!=prepared.patient||id(row.get("intake_id"))!=prepared.intake||id(row.get("action_id"))!=action||id(row.get("actor_id"))!=prepared.actor)throw conflict("CONSUMPTION_CONTEXT_CHANGED");prepared.register("medication_execution_event",event);}
    /** The mutable resolution names one immutable receipt, which names at most one previous settled receipt.
     * No scan for the last successful event and no recursive evidence-pointer traversal. */
    public Map<String,Object> settlementAnchor(long stock,long intake,long patient) {
        Map<String,Object> r=one("SELECT * FROM stock_intake_resolution WHERE intake_id=?",intake);
        if(r==null)return null;
        if(id(r.get("stock_id"))!=stock||id(r.get("patient_id"))!=patient)throw conflict("SETTLEMENT_EVIDENCE_INVALID");
        Map<String,Object> e=one("SELECT * FROM stock_event WHERE id=?",r.get("stock_event_id"));
        validateSettlement(e,stock,intake,patient);
        if(!Objects.equals(e.get("action_id"),r.get("current_action_id"))||!Objects.equals(e.get("execution_event_id"),r.get("execution_event_id"))||!Objects.equals(e.get("settlement_status"),r.get("state")))throw conflict("SETTLEMENT_EVIDENCE_INVALID");
        return e;
    }
    public Map<String,Object> lastSettled(long stock,long intake,long patient) {
        Map<String,Object> e=settlementAnchor(stock,intake,patient);
        if(e==null)return null;
        if(settled(e))return e;
        Map<String,Object> evidence=object((String)e.get("evidence_json"));
        if(!"UNRECONCILED".equals(e.get("settlement_status"))||!evidence.containsKey("previousSettledEventId"))throw conflict("SETTLEMENT_EVIDENCE_INVALID");
        if(evidence.get("previousSettledEventId")==null)return null;
        Map<String,Object> previous=one("SELECT * FROM stock_event WHERE id=?",evidence.get("previousSettledEventId"));
        validateSettlement(previous,stock,intake,patient);
        if(!settled(previous)||id(previous.get("id"))>=id(e.get("id"))||id(previous.get("aggregate_version"))>=id(e.get("aggregate_version")))throw conflict("SETTLEMENT_EVIDENCE_INVALID");
        return previous;
    }
    private static boolean settled(Map<String,Object> e) {
        return e.get("settled_action_id")!=null&&Objects.equals(e.get("settled_action_id"),e.get("action_id"))&&Arrays.asList("APPLIED","COUNT_INCLUDED","NO_DELTA").contains(e.get("settlement_status"));
    }
    private static void validateSettlement(Map<String,Object> e,long stock,long intake,long patient) {
        if(e==null||id(e.get("stock_id"))!=stock||id(e.get("intake_id"))!=intake||id(e.get("patient_id"))!=patient||!hash((String)e.get("evidence_json")).equals(e.get("evidence_hash")))throw conflict("SETTLEMENT_EVIDENCE_INVALID");
    }
    private static void add(Map<String,SortedSet<Long>> graph,String table,Object value)  {
        if(value!=null)graph.get(table).add(id(value));
    }
    private static void user(Set<Long> users,Object value)  {
        if(value!=null)users.add(id(value));
    }
    /** Complete indexable history, bounded to 200 rows in memory, a fixed endpoint, a hash chain, and an honest time budget. NULL links are never filtered out. */
    public Map<String,Object> watermark(long patient,long medication,boolean actions)  {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        HistoryDigest digest=new HistoryDigest(actions?"care_intake_action":"medication_stock_movement",deadline);
        if(actions)  {
            // Use the existing (patient_id,medication_id,id) intake and (intake_id,id) action indexes.
            // A global action-id join would repeatedly sort all of a large patient's history for each page.
            long endpoint=endpoint("SELECT MAX(id) AS endpoint FROM medication_intake WHERE patient_id=? AND medication_id=?",patient,medication),after=0;
            while(after<endpoint)  {
                digest.check();
                List<Map<String,Object>> intakes=rows("SELECT id FROM medication_intake WHERE patient_id=? AND medication_id=? AND id>? AND id<=? ORDER BY id LIMIT 200",patient,medication,after,endpoint);
                if(intakes.isEmpty())throw conflict("HISTORY_CHANGED");
                for(Map<String,Object> intake:intakes)  {
                    digest.check();
                    long intakeId=id(intake.get("id")),actionEnd=endpoint("SELECT MAX(id) AS endpoint FROM care_intake_action WHERE intake_id=?",intakeId),actionAfter=0;
                    while(actionAfter<actionEnd)  {
                        digest.check();
                        List<Map<String,Object>> page=rows("SELECT * FROM care_intake_action WHERE intake_id=? AND id>? AND id<=? ORDER BY id LIMIT 200",intakeId,actionAfter,actionEnd);
                        if(page.isEmpty())throw conflict("HISTORY_CHANGED");
                        for(Map<String,Object> row:page)  {
                            if(id(row.get("patient_id"))!=patient)throw conflict("HISTORY_IDENTITY_MISMATCH");
                            digest.accept(row);
                            actionAfter=id(row.get("id"));
                        }
                    }
                    if(endpoint("SELECT MAX(id) AS endpoint FROM care_intake_action WHERE intake_id=?",intakeId)!=actionEnd)throw conflict("HISTORY_CHANGED");
                    after=intakeId;
                }
            }
            if(endpoint("SELECT MAX(id) AS endpoint FROM medication_intake WHERE patient_id=? AND medication_id=?",patient,medication)!=endpoint)throw conflict("HISTORY_CHANGED");
            return digest.value(endpoint);
        }
        long endpoint=endpoint("SELECT MAX(id) AS endpoint FROM medication_stock_movement WHERE patient_id=? AND medication_id=?",patient,medication),after=0;
        while(after<endpoint)  {
            digest.check();
            List<Map<String,Object>> page=rows("SELECT * FROM medication_stock_movement WHERE patient_id=? AND medication_id=? AND id>? AND id<=? ORDER BY id LIMIT 200",patient,medication,after,endpoint);
            if(page.isEmpty())throw conflict("HISTORY_CHANGED");
            for(Map<String,Object> row:page)  {
                digest.accept(row);
                after=id(row.get("id"));
            }
        }
        if(endpoint("SELECT MAX(id) AS endpoint FROM medication_stock_movement WHERE patient_id=? AND medication_id=?",patient,medication)!=endpoint)throw conflict("HISTORY_CHANGED");
        return digest.value(endpoint);
    }
    public Map<String,Object> resolutionWatermark(long stock,long actor,Instant reviewedAt)  {
        long end=endpoint("SELECT MAX(id) AS endpoint FROM stock_intake_resolution WHERE stock_id=?",stock),after=0;
        HistoryDigest digest=new HistoryDigest("stock_intake_resolution",System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
        while(after<end)  {
            digest.check();
            List<Map<String,Object>> page=rows("SELECT * FROM stock_intake_resolution WHERE stock_id=? AND id>? AND id<=? ORDER BY id LIMIT 200",stock,after,end);
            if(page.isEmpty())throw conflict("HISTORY_CHANGED");
            for(Map<String,Object> row:page)  {
                digest.accept(row);
                after=id(row.get("id"));
            }
        }
        Map<String,Object> value=digest.value(end);
        value.put("reviewedBy",actor);
        value.put("reviewedAt",reviewedAt.toString());
        return value;
    }
    private long endpoint(String sql,Object...args)  {
        Object value=one(sql,args).get("endpoint");
        return value==null?0:id(value);
    }
    private static final class HistoryDigest  {
        private final long deadline;
        private String chain;
        private long total;
        private Instant latest;
        HistoryDigest(String table,long deadline)  {
            this.deadline=deadline;
            chain=hash("STOCK_HISTORY_V1:"+table);
        }
        void check()  {
            if(System.nanoTime()>deadline)throw new WorkflowException(503,"HISTORY_TIMEOUT","The complete ledger could not be checked in time. No changes were applied.");
        }
        void accept(Map<String,Object> row)  {
            check();
            Map<String,Object> evidence=new LinkedHashMap<>(row);
            for(Map.Entry<String,Object> e:evidence.entrySet())if(e.getValue() instanceof Instant)e.setValue(e.getValue().toString());
            chain=hash(chain+":"+json(evidence));
            if(++total>9007199254740991L)throw conflict("HISTORY_OVERFLOW");
            Object actual=row.get("actual_at_utc");
            if(actual!=null)  {
                Instant at=actual instanceof Instant?(Instant)actual:((Timestamp)actual).toInstant();
                if(latest==null||at.isAfter(latest))latest=at;
            }
        }
        Map<String,Object> value(long endpoint)  {
            check();
            return map("format","STOCK_HISTORY_V1","count",total,"endpoint",endpoint,"hash",chain,"latestActualAt",latest==null?null:latest.toString());
        }
    }
    public long unresolved(long patient,long medication)  {
        Map<String,Object> n=one("SELECT COUNT(*) AS total FROM medication_intake i LEFT JOIN care_intake_action a ON a.id=i.latest_action_id AND a.patient_id=i.patient_id AND a.intake_id=i.id LEFT JOIN stock_intake_resolution r ON r.intake_id=i.id AND r.patient_id=i.patient_id AND r.medication_id=i.medication_id AND r.current_action_id=a.id LEFT JOIN stock_event e ON e.id=r.stock_event_id AND e.settled_action_id=a.id AND e.action_id=a.id AND e.intake_id=i.id AND e.patient_id=i.patient_id AND e.stock_id=r.stock_id AND e.execution_event_id=a.execution_event_id AND e.settlement_status IN ('APPLIED','COUNT_INCLUDED','NO_DELTA') WHERE i.patient_id=? AND i.medication_id=? AND (i.status='TAKEN' OR a.action_kind='CORRECTION') AND (a.id IS NULL OR e.id IS NULL OR e.settlement_status='UNRECONCILED')",patient,medication);
        return id(n.get("total"));
    }
    public static WorkflowException conflict(String code)  {
        return new WorkflowException(409,code,"The stock review changed. Refresh and review the current evidence.");
    }
    public Map<String,Object> one(String sql,Object...args)  {
        List<Map<String,Object>> rows=jdbc.query(sql,ps->  {
            ps.setQueryTimeout(5);
            bind(ps,args);
        }
        ,(rs,i)->  {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int n=1; n<=md.getColumnCount(); n++)  {
                Object value=rs.getObject(n);
                if(value instanceof Clob)value=rs.getString(n);
                if(value instanceof Timestamp)value=rs.getTimestamp(n,utc()).toInstant();
                row.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),value);
            }
            return row;
        }
        );
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Map<String,Object>> rows(String sql,Object...args)  {
        return jdbc.query(sql,ps->  {
            ps.setQueryTimeout(5);
            bind(ps,args);
        }
        ,(rs,i)->  {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int n=1; n<=md.getColumnCount(); n++)  {
                Object value=rs.getObject(n);
                if(value instanceof Clob)value=rs.getString(n);
                if(value instanceof Timestamp)value=rs.getTimestamp(n,utc()).toInstant();
                row.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),value);
            }
            return row;
        }
        );
    }
    public long insert(String sql,Object...args)  {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(c->  {
            PreparedStatement ps=c.prepareStatement(sql,new String[]  {
                "id"
            }
            );
            bind(ps,args);
            return ps;
        }
        ,key);
        if(key.getKey()==null)throw new IllegalStateException("Missing generated identity");
        return key.getKey().longValue();
    }
    public int update(String sql,Object...args)  {
        return jdbc.update(sql,ps->bind(ps,args));
    }
    public static void bind(PreparedStatement ps,Object[] args)throws SQLException  {
        for(int n=0; n<args.length; n++)if(args[n] instanceof Instant)time(ps,n+1,(Instant)args[n]);
        else if(args[n] instanceof LocalDateTime)ps.setTimestamp(n+1,Timestamp.from(((LocalDateTime)args[n]).toInstant(ZoneOffset.UTC)),utc());
        else ps.setObject(n+1,args[n]);
    }
}
