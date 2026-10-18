package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.sql.Connection;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;

/** A 患者先行；V 完整计划集合先行。两种能力不可互转，也不允许锁尾追加身份。 */
@Component
public final class VisitFollowUpAdmission {
    private final VisitFollowUpRepository repo;
    private final VisitFollowUpAuthorizationService auth;
    private final VisitFollowUpProperties properties;
    private final VisitFollowUpSchema schema;
    private final HouseholdAuthorityLocks household;
    private final HouseholdWorkflowSchema householdSchema;
    public VisitFollowUpAdmission(VisitFollowUpRepository repo,VisitFollowUpAuthorizationService auth,VisitFollowUpProperties properties,VisitFollowUpSchema schema,HouseholdAuthorityLocks household,HouseholdWorkflowSchema householdSchema) {
        this.repo=Objects.requireNonNull(repo);
        this.auth=Objects.requireNonNull(auth);
        this.properties=Objects.requireNonNull(properties);
        this.schema=Objects.requireNonNull(schema);
        this.household=Objects.requireNonNull(household);
        this.householdSchema=Objects.requireNonNull(householdSchema);
    }
    public Context lockAppointmentOrVisitSource(long actor,Command command) {
        if(command==null||command.isVisitBinding())throw integrity();
        VisitFollowUpSchema.Inspection inspected=begin();VisitFollowUpSchema.Availability availability=inspected.getAvailability();
        repo.lock("patient",command.getPatientId(),false);
        Discovery before=discover(actor,command,false);
        HouseholdAuthorityLocks.LockedAuthority token=household.lockPatientAndSubjects(command.getPatientId(),before.subjects);
        lockSources(before,false);
        lockSet(before,"family_assistance",false);
        lockSet(before,"notification_delivery_log",true);
        lockSet(before,"visit_followup_state",false);
        lockSet(before,"visit_followup_event",false);
        if(before.receipt!=null)repo.command(actor,command.getCommandKey(),true);
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();
        for(String table:Arrays.asList("care_item","care_appointment","family_assistance"))if(before.ids.containsKey(table))selected.put(table,before.ids.get(table));
        if(!selected.isEmpty())householdSchema.requireSelectedRowsWithStorage(token,command.getPatientId(),actor,selected,inspected.getCompatibility());
        Discovery after=discover(actor,command,false);
        requireSame(before,after);
        requireSourceAuthority(actor,command,after);
        return new Context(new Scope(actor,command,after,token,false,availability));
    }
    public VisitBindingContext lockVisitBinding(long actor,Command command) {
        if(command==null||!command.isVisitBinding()||command.getSourceKind()!=SourceKind.VISIT_RECORD)throw integrity();
        VisitFollowUpSchema.Inspection inspected=begin();VisitFollowUpSchema.Availability availability=inspected.getAvailability();
        Discovery before=discover(actor,command,true);
        // 完整旧/新 plan union 在任何患者或身份锁之前全部取得 X。
        lockSet(before,"doctor_care_plan",false);
        repo.lock("patient",command.getPatientId(),true);
        for(String table:Arrays.asList("sys_user","sys_role"))lockSet(before,table,true);
        for(Map<String,Object> mapping:before.authority.rows().get("sys_user_role"))repo.lock("sys_user_role",id(mapping.get("id")),true);
        for(String table:Arrays.asList("care_access_grant","doctor_patient_assignment","care_member","care_nurse_assignment"))lockSet(before,table,true);
        lockSources(before,true);
        lockSet(before,"care_plan_revision",true);
        lockSet(before,"care_plan_action",true);
        lockSet(before,"notification_delivery_log",true);
        lockSet(before,"visit_followup_state",false);
        lockSet(before,"visit_followup_action_link",false);
        lockSet(before,"visit_followup_event",false);
        if(before.receipt!=null)repo.command(actor,command.getCommandKey(),true);
        Discovery after=discover(actor,command,true);
        requireSame(before,after);
        requireSourceAuthority(actor,command,after);
        return new VisitBindingContext(new Scope(actor,command,after,null,true,availability));
    }
    private VisitFollowUpSchema.Inspection begin() {
        WorkflowData.mutationTransaction(repo.jdbcTemplate());
        Connection connection=DataSourceUtils.getConnection(Objects.requireNonNull(repo.jdbcTemplate().getDataSource()));
        try {
            VisitFollowUpSchema.Inspection inspected=schema.inspectStorage(connection);inspected.getAvailability().requireReadable();return inspected;
        }finally {
            DataSourceUtils.releaseConnection(connection,repo.jdbcTemplate().getDataSource());
        }
    }
    private void lockSet(Discovery d,String table,boolean shared) {
        for(Long id:d.ids.getOrDefault(table,new TreeSet<>()))repo.lock(table,id,shared);
    }
    private void lockSources(Discovery d,boolean shared) {
        for(String table:Arrays.asList("care_item","care_appointment","visit_record"))lockSet(d,table,shared);
    }
    private void requireSame(Discovery a,Discovery b) {
        if(!a.signature().equals(b.signature()))throw VisitFollowUpAuthorizationService.changed();
    }
    private void requireSourceAuthority(long actor,Command c,Discovery d) {
        auth.requireCommand(d.authority,actor,c);
        for(SourceRef source:d.sources.keySet()) {
            if(source.getKind().isAppointment()) {
                if(!d.authority.canAppointmentRead(actor))throw VisitFollowUpAuthorizationService.denied();
                repo.requireOrdinaryLegacySource(source);
            }
            else if(!d.authority.canVisitRead(actor))throw VisitFollowUpAuthorizationService.denied();
        }
        NativeSource source=c.getSource()==null?null:d.sources.get(c.getSource());
        if(c.isVisitBinding()&&(source==null||!"PUBLISHED".equals(source.getValues().get("status"))))throw VisitFollowUpAuthorizationService.changed();
        if(c.getOperation()==Operation.COMPLETE_SELECTED_APPOINTMENT&&(source==null||!Objects.equals(source.getDoctorId(),actor)||!d.authority.isActiveDoctor(actor)))throw VisitFollowUpAuthorizationService.denied();
        if(d.receipt==null&&c.isVisitBinding())for(Map<String,Object> link:requestedLinks(c)) {
            long pid=id(link.get("planId")),rid=id(link.get("revisionId")),aid=id(link.get("actionId"));
            Map<String,Object> plan=d.plans.get(pid),revision=d.revisions.get(rid),action=d.actions.get(aid);
            if(plan==null||revision==null||action==null||number(plan.get("workflow_version"))!=1||!"ACTIVE".equals(plan.get("lifecycle"))||number(plan.get("current_revision_id"))!=rid||!"PUBLISHED".equals(revision.get("status"))||!"FOLLOW_UP".equals(revision.get("plan_type"))||!Arrays.asList("OPEN","NEEDS_HELP").contains(action.get("status"))||action.get("due_at")==null||!d.authority.canPlanRecord(number(action.get("assigned_user_id"))))throw VisitFollowUpAuthorizationService.changed();
        }
    }
    private Discovery discover(long actor,Command c,boolean binding) {
        id(actor);
        Discovery d=new Discovery();
        d.subjects.add(actor);
        Map<String,Object> patient=repo.one("SELECT id,user_id FROM patient WHERE id=?",c.getPatientId());
        if(patient==null)throw VisitFollowUpAuthorizationService.denied();
        d.subjects.add(id(patient.get("user_id")));
        d.add("patient",c.getPatientId());
        d.receipt=repo.command(actor,c.getCommandKey(),false);
        if(d.receipt!=null) {
            if(number(d.receipt.get("patient_id"))!=c.getPatientId())throw new WorkflowException(409,"COMMAND_CONFLICT","The command key belongs to a different request.");
            d.receiptCount=1;
            Object raw=d.receipt.get("result_json");
            if(raw==null||number(d.receipt.get("receipt_bytes"))>512)throw integrity();
            Map<String,Object> receipt=storedReceipt(String.valueOf(raw));
            SourceRef replay=new SourceRef(SourceKind.valueOf(String.valueOf(receipt.get("sourceKind"))),id(receipt.get("sourceId")));
            addSource(d,replay,c.getPatientId());
            d.add("visit_followup_event",id(receipt.get("eventId")));
            if(receipt.get("successorId")!=null)addSource(d,new SourceRef(SourceKind.valueOf(String.valueOf(receipt.get("successorKind"))),id(receipt.get("successorId"))),c.getPatientId());
        }
        if(c.getSource()!=null)addSource(d,c.getSource(),c.getPatientId());
        for(String key:Arrays.asList("successor","originAppointment")) {
            Map<String,Object> target=object(c.getBody().get(key));
            if(target!=null)addSource(d,new SourceRef(SourceKind.valueOf(String.valueOf(target.get("sourceKind"))),id(target.get("sourceId"))),c.getPatientId());
        }
        Object responsible=c.getBody().get("responsibleUserId");
        if(responsible!=null)d.subjects.add(id(responsible));
        Map<String,Object> schedule=object(c.getBody().get("schedule"));
        if(schedule!=null&&schedule.get("doctorUserId")!=null)d.subjects.add(id(schedule.get("doctorUserId")));
        if(!binding) {
            Set<SourceRef> seeds=new LinkedHashSet<>(d.sources.keySet());
            // Include the old current target itself as a seed, once. This is a bounded
            // local neighbourhood, never a recursive chain walk or late lock expansion.
            for(SourceRef source:new ArrayList<>(seeds)) {
                Map<String,Object> state=d.statesBySource.get(source);
                if(state!=null&&state.get("successor_kind")!=null) {
                    SourceKind kind=SourceKind.valueOf(String.valueOf(state.get("successor_kind")));
                    SourceRef oldTarget=new SourceRef(kind,id(state.get(kind==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id")));
                    addSource(d,oldTarget,c.getPatientId());seeds.add(oldTarget);
                }
            }
            for(SourceRef source:seeds)addNeighbours(d,source,c.getPatientId());
            for(NativeSource source:new ArrayList<>(d.sources.values()))addAssistance(d,source,c.getPatientId());
        }else {
            // 只读当前版 links；历史不会被截断后伪装为完整当前集合。
            Map<String,Object> own=c.getSource()==null?null:d.statesBySource.get(c.getSource());
            if(own!=null) {
                long state=id(own.get("id"));
                d.links=repo.links(state,number(own.get("disposition_revision")));
                for(Map<String,Object> link:d.links) {
                    d.add("visit_followup_action_link",id(link.get("id")));
                    addPlanAction(d,id(link.get("plan_id")),id(link.get("revision_id")),id(link.get("action_id")),c.getPatientId());
                }
            }
            for(Map<String,Object> link:requestedLinks(c))addPlanAction(d,id(link.get("planId")),id(link.get("revisionId")),id(link.get("actionId")),c.getPatientId());
        }
        if(c.getBody().get("eventId")!=null)d.add("visit_followup_event",id(c.getBody().get("eventId")));
        d.authority=auth.snapshot(c.getPatientId(),d.subjects,50);
        for(Map.Entry<String,List<Map<String,Object>>> group:d.authority.rows().entrySet())for(Map<String,Object> row:group.getValue()) {
            d.add(group.getKey(),id(row.get("id")));
            if("sys_user_role".equals(group.getKey())&&row.get("role_code")!=null)d.add("sys_role",id(row.get("role_id")));
        }
        d.checkBudget();
        for(Long event:d.ids.getOrDefault("visit_followup_event",new TreeSet<>())) {
            Map<String,Object> row=repo.event(event);
            if(row==null||number(row.get("patient_id"))!=c.getPatientId())throw integrity();
            d.events.put(event,row);
        }
        return d;
    }
    private void addSource(Discovery d,SourceRef source,long patient) {
        if(d.sources.containsKey(source))return;
        NativeSource nativeSource=repo.source(source);
        if(nativeSource.getPatientId()!=patient)throw VisitFollowUpAuthorizationService.denied();
        d.sources.put(source,nativeSource);
        d.add(source.getKind().tableName(),source.getId());
        if(nativeSource.getDoctorId()!=null)d.subjects.add(nativeSource.getDoctorId());
        for(String subject:Arrays.asList("user_id","assigned_user_id","actor_id"))if(nativeSource.getValues().get(subject)!=null)d.subjects.add(id(nativeSource.getValues().get(subject)));
        Map<String,Object> state=repo.state(source);
        if(state!=null) {
            if(number(state.get("patient_id"))!=patient)throw integrity();
            d.statesBySource.put(source,state);
            d.states.put(id(state.get("id")),state);
            d.add("visit_followup_state",id(state.get("id")));
            // FK证明也属于同一50行预算；先发现精确日志，锁序固定在state之前。
            if(state.get("reminder_delivery_log_id")!=null){long log=id(state.get("reminder_delivery_log_id"));d.add("notification_delivery_log",log);Map<String,Object> row=repo.reminderDeliveryLog(log);if(row==null)throw integrity();d.logs.put(log,row);}
            for(String subject:Arrays.asList("responsible_user_id","created_by"))if(state.get(subject)!=null)d.subjects.add(id(state.get(subject)));
            for(String event:Arrays.asList("current_fact_event_id","last_native_event_id","decision_event_id"))if(state.get(event)!=null)d.add("visit_followup_event",id(state.get(event)));
            if(state.get("origin_appointment_state_id")!=null) {
                long origin=id(state.get("origin_appointment_state_id"));
                Map<String,Object> originRow=repo.state(origin);
                if(originRow==null||number(originRow.get("patient_id"))!=patient||"VISIT_RECORD".equals(originRow.get("source_kind")))throw integrity();
                addSource(d,reference(originRow),patient);
            }
        }
        if(source.getKind()==SourceKind.VISIT_RECORD&&nativeSource.getValues().get("appointment_id")!=null)addSource(d,new SourceRef(SourceKind.CARE_APPOINTMENT,id(nativeSource.getValues().get("appointment_id"))),patient);
        d.checkBudget();
    }
    private void addNeighbours(Discovery d,SourceRef source,long patient) {
        if(!source.getKind().isAppointment())return;
        Map<String,Object> s=d.statesBySource.get(source);
        if(s!=null&&s.get("successor_kind")!=null) {
            SourceKind kind=SourceKind.valueOf(String.valueOf(s.get("successor_kind")));
            if(kind!=source.getKind())throw integrity();
            Object sid=s.get(kind==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id");
            addSource(d,new SourceRef(kind,id(sid)),patient);
        }
        for(Map<String,Object> previous:repo.predecessor(source)) {
            if(number(previous.get("patient_id"))!=patient)throw integrity();
            addSource(d,reference(previous),patient);
        }
    }
    private void addAssistance(Discovery d,NativeSource source,long patient) {
        if(!source.getReference().getKind().isAppointment())return;
        Long next=nullable(source.getValues().get("family_assistance_id"));
        long marker=number(source.getValues().get("family_workflow_version"));
        if((next==null&&marker!=0)||(next!=null&&marker!=1))throw integrity();
        Set<Long> visited=new HashSet<>();
        while(next!=null) {
            if(!visited.add(next))throw integrity();
            Map<String,Object> row=repo.one("SELECT id,patient_id,source_kind,care_item_id,care_appointment_id,request_kind,originator_id,assigned_user_id,accepted_by,handled_by,previous_assistance_id FROM family_assistance WHERE id=?",next);
            if(row==null||number(row.get("patient_id"))!=patient||!"ACCOMPANIMENT".equals(row.get("request_kind")))throw integrity();
            String kind=source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"CARE_ITEM":"CARE_APPOINTMENT";
            if(!kind.equals(row.get("source_kind"))||number(row.get(source.getReference().getKind().idColumn()))!=source.getReference().getId())throw integrity();
            d.add("family_assistance",next);
            d.assistance.put(next,row);
            for(String user:Arrays.asList("originator_id","assigned_user_id","accepted_by","handled_by"))if(row.get(user)!=null)d.subjects.add(id(row.get(user)));
            next=nullable(row.get("previous_assistance_id"));
            d.checkBudget();
        }
    }
    private void addPlanAction(Discovery d,long planId,long revisionId,long actionId,long patient) {
        Map<String,Object> plan=d.plans.computeIfAbsent(planId,repo::plan),revision=d.revisions.computeIfAbsent(revisionId,repo::revision),action=d.actions.computeIfAbsent(actionId,repo::action);
        if(plan==null||revision==null||action==null||number(plan.get("patient_id"))!=patient||number(revision.get("plan_id"))!=planId||number(action.get("plan_id"))!=planId||number(action.get("revision_id"))!=revisionId||number(action.get("patient_id"))!=patient)throw VisitFollowUpAuthorizationService.denied();
        d.add("doctor_care_plan",planId);
        d.add("care_plan_revision",revisionId);
        d.add("care_plan_action",actionId);
        d.subjects.add(id(action.get("assigned_user_id")));
        d.checkBudget();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        return value==null?null:(Map<String,Object>)value;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> requestedLinks(Command command) {
        Object links=command.getBody().get("links");
        return links==null?Collections.emptyList():(List<Map<String,Object>>)links;
    }
    private static final class Discovery {
        final Map<String,SortedSet<Long>> ids=new TreeMap<>();
        final SortedSet<Long> subjects=new TreeSet<>();
        final Map<SourceRef,NativeSource> sources=new LinkedHashMap<>();
        final Map<SourceRef,Map<String,Object>> statesBySource=new LinkedHashMap<>();
        final Map<Long,Map<String,Object>> states=new TreeMap<>(),assistance=new TreeMap<>(),plans=new TreeMap<>(),revisions=new TreeMap<>(),actions=new TreeMap<>(),events=new TreeMap<>(),logs=new TreeMap<>();
        List<Map<String,Object>> links=Collections.emptyList();
        Map<String,Object> receipt;
        int receiptCount;
        VisitFollowUpAuthorizationService.Snapshot authority;
        void add(String table,long id) {
            ids.computeIfAbsent(table,k->new TreeSet<>()).add(id);
            checkBudget();
        }
        void checkBudget() {
            int count=receiptCount;
            for(Collection<Long> values:ids.values())count+=values.size();
            if(count>50||subjects.size()>50)throw VisitFollowUpAuthorizationService.limit();
        }
        String signature() {
            Map<String,Object> sourceSignatures=new TreeMap<>();
            sources.forEach((source,value)->sourceSignatures.put(source.toString(),value.getFingerprint()));
            return canonical(WorkflowData.map("ids",ids,"subjects",subjects,"sources",sourceSignatures,"states",states,"assistance",assistance,"plans",plans,"revisions",revisions,"actions",actions,"events",events,"logs",logs,"links",links,"receipt",receipt,"authority",authority.signature()));
        }
    }
    public static final class Context {
        private final Scope scope;
        private Context(Scope scope) {
            this.scope=scope;
        }
        Scope scope() {
            return scope;
        }
        void requireAppointmentWrite(SourceRef source) {
            scope.requireOpen();
            if(scope.binding||!scope.sources.contains(source))throw integrity();
            if(scope.creation!=null&&scope.creation.permits(scope,source))return;
            if(source.equals(scope.command.getSource()))return;
            Map<String,Object> target=object(scope.command.getBody().get("successor"));
            if(Arrays.asList(Operation.LINK_NEXT,Operation.REPLACE_NEXT).contains(scope.command.getOperation())&&target!=null&&source.getKind().name().equals(target.get("sourceKind"))&&source.getId()==number(target.get("sourceId")))return;
            throw integrity();
        }
        void requireAppointmentState(long state) {
            scope.requireOpen();
            if(scope.binding||!scope.stateSources.containsKey(state))throw integrity();
            requireAppointmentWrite(scope.stateSources.get(state));
        }
        HouseholdAuthorityLocks.LockedAuthority authority() {
            scope.requireLive();
            return scope.authority;
        }
        public long getPatientId() {
            scope.requireLive();
            return scope.command.getPatientId();
        }
    }
    public static final class VisitBindingContext {
        private final Scope scope;
        private VisitBindingContext(Scope scope) {
            this.scope=scope;
        }
        Scope scope() {
            return scope;
        }
        void requireBindingWrite() {
            scope.requireOpen();
            if(!scope.binding)throw integrity();
        }
        void requireBindingState(long state) {
            requireBindingWrite();
            if(!Objects.equals(scope.stateSources.get(state),scope.command.getSource()))throw integrity();
        }
        void requireRequestedLink(long plan,long revision,long action,String kind) {
            requireBindingWrite();
            for(Map<String,Object> link:requestedLinks(scope.command))if(number(link.get("planId"))==plan&&number(link.get("revisionId"))==revision&&number(link.get("actionId"))==action&&kind.equals(link.get("kind")))return;
            throw integrity();
        }
        public long getPatientId() {
            scope.requireLive();
            return scope.command.getPatientId();
        }
    }
    final class Scope {
        final long actor;
        final Command command;
        final boolean binding;
        final HouseholdAuthorityLocks.LockedAuthority authority;
        final SortedSet<Long> subjects;
        final Map<String,SortedSet<Long>> admitted;
        final Set<SourceRef> sources;
        final Map<Long,SourceRef> stateSources=new TreeMap<>(),touchedStates=new TreeMap<>();
        final Map<Long,PendingEvent> pending=new TreeMap<>();
        final Map<Long,CompletedState> completed=new TreeMap<>();
        final Map<String,Object> admittedReceipt;
        final Object repositoryOwner;
        final Connection connection;
        final Thread thread=Thread.currentThread();
        final String authoritySignature;
        boolean live=true,started=false,mutationActive=false,sealed=false,creationAttempted=false;
        VisitFollowUpRepository.CreatedSource creation;
        final VisitFollowUpSchema.Availability availability;
        final Map<Long,Long> reminderPointers=new TreeMap<>();
        Scope(long actor,Command command,Discovery d,HouseholdAuthorityLocks.LockedAuthority authority,boolean binding,VisitFollowUpSchema.Availability availability) {
            this.availability=availability;
            d.states.forEach((id,row)->reminderPointers.put(id,nullable(row.get("reminder_delivery_log_id"))));
            this.actor=actor;
            this.repositoryOwner=repo.ownershipIdentity();
            this.command=command;
            this.authority=authority;
            this.binding=binding;
            subjects=Collections.unmodifiableSortedSet(new TreeSet<>(d.subjects));
            admitted=d.ids;
            sources=new LinkedHashSet<>(d.sources.keySet());
            d.states.forEach((id,row)->stateSources.put(id,reference(row)));
            admittedReceipt=d.receipt;
            authoritySignature=d.authority.signature();
            connection=DataSourceUtils.getConnection(Objects.requireNonNull(repo.jdbcTemplate().getDataSource()));
            DataSourceUtils.releaseConnection(connection,repo.jdbcTemplate().getDataSource());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    live=false;
                }
            });
        }
        boolean requiresCreation() {
            Operation operation=command.getOperation();
            return operation==Operation.CREATE_VISIT||operation==Operation.CREATE_NEXT
                ||operation==Operation.REPLACE_NEXT&&command.getBody().get("schedule")!=null;
        }
        java.time.Instant now() {
            return properties.now();
        }
        VisitFollowUpRepository repository() {
            return repo;
        }
        void requireEnabled() {
            availability.requireWritable(properties.isEnabled());
        }
        void requireLive() {
            if(!live||thread!=Thread.currentThread())throw VisitFollowUpAuthorizationService.changed();
            WorkflowData.mutationTransaction(repo.jdbcTemplate());
            Connection current=DataSourceUtils.getConnection(Objects.requireNonNull(repo.jdbcTemplate().getDataSource()));
            try {
                if(current!=connection)throw VisitFollowUpAuthorizationService.changed();
            }finally {
                DataSourceUtils.releaseConnection(current,repo.jdbcTemplate().getDataSource());
            }
            if(!binding)household.requireLive(authority,command.getPatientId(),actor);
        }
        void requireOpen() {
            requireLive();
            if(!started||!mutationActive||sealed)throw integrity();
        }
        void start(long actor,Command command,boolean binding) {
            requireLive();
            if(started||this.actor!=actor||this.binding!=binding||!envelope(actor,this.command).equals(envelope(actor,command)))throw integrity();
            started=true;
            requireCurrent();
        }
        void requireCurrent() {
            requireLive();
            for(Map.Entry<Long,Long> pointer:reminderPointers.entrySet()){Map<String,Object> state=repo.state(pointer.getKey());if(state==null||!Objects.equals(pointer.getValue(),nullable(state.get("reminder_delivery_log_id"))))throw VisitFollowUpAuthorizationService.changed();}
            VisitFollowUpAuthorizationService.Snapshot current=currentAuthority();
            if(!authoritySignature.equals(current.signature()))throw VisitFollowUpAuthorizationService.changed();
            auth.requireCommand(current,actor,command);
            if(binding&&admittedReceipt==null)for(Map<String,Object> link:requestedLinks(command)) {
                Map<String,Object> action=repo.action(id(link.get("actionId")));
                if(action==null||!current.canPlanRecord(number(action.get("assigned_user_id"))))throw VisitFollowUpAuthorizationService.denied();
            }
            for(SourceRef source:sources) {
                NativeSource row=repo.source(source);
                if(row.getPatientId()!=command.getPatientId())throw VisitFollowUpAuthorizationService.denied();
                if(source.getKind().isAppointment()) {
                    if(!current.canAppointmentRead(actor))throw VisitFollowUpAuthorizationService.denied();
                    repo.requireOrdinaryLegacySource(source);
                }else if(!current.canVisitRead(actor))throw VisitFollowUpAuthorizationService.denied();
            }
        }
        VisitFollowUpAuthorizationService.Snapshot currentAuthority() {
            return auth.snapshot(command.getPatientId(),subjects,50);
        }
        boolean contains(String table,long id) {
            return admitted.getOrDefault(table,new TreeSet<>()).contains(id);
        }
        void registerState(long state,SourceRef source) {
            if(!sources.contains(source))throw integrity();
            stateSources.put(state,source);
            touchedStates.put(state,source);
        }
        void pendingEvent(long state,long event,long version,Map<String,Object> after,Set<String> changes) {
            if(pending.containsKey(state))throw integrity();
            pending.put(state,new PendingEvent(event,version,after,changes));
            touchedStates.put(state,stateSources.get(state));
        }
        PendingEvent pending(long state,long event) {
            PendingEvent p=pending.get(state);
            if(p==null||p.event!=event)throw integrity();
            return p;
        }
        void completedState(long state,long event,long version) {
            completed.put(state,new CompletedState(event,version));
        }
    }
    static final class PendingEvent {
        final long event,version;
        final Map<String,Object> after;
        final Set<String> changes;
        PendingEvent(long event,long version,Map<String,Object> after,Set<String> changes) {
            this.event=event;
            this.version=version;
            this.after=after;
            this.changes=changes;
        }
    }
    static final class CompletedState {
        final long event,version;
        CompletedState(long event,long version) {
            this.event=event;
            this.version=version;
        }
    }
}
