package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.medicationexecution.MedicationBoundaryService.at;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
/** Only the existing reminder generator/tick calls this adapter. No second scheduler or delivery transport. */
@Service
public class MedicationTrackedReminderAdapter {
    private static final Logger log=LoggerFactory.getLogger(MedicationTrackedReminderAdapter.class);
    private static final List<String> LANES=Arrays.asList("SCHEDULED","ACTIVE","ENDED","SUPERSEDED");
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionEventStore events;
    private final MedicationBoundaryService boundary;
    // Restarting cursors may revisit a page; persistent interval watermarks and unique slots make that harmless.
    private final LinkedHashMap<String,Cursor> cursors=new LinkedHashMap<String,Cursor>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Cursor> entry) {
            return size()>128;
        }
    }
    ;
    private final Map<String,Cursor> globalCursors=new HashMap<>();
    private Instant dueAfter,dueHigh;
    private long dueAfterId,dueHighId;
    private static final class Cursor {
        long after,high;
    }
    public MedicationTrackedReminderAdapter(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,MedicationExecutionEventStore events,MedicationBoundaryService boundary) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
        this.boundary=boundary;
    }
    public synchronized void ensureDailyTasks(long patientId,LocalDate date) {
        if(storage())discover(properties.now(),patientId,date);
    }
    public synchronized void materializeDue(Instant now) {
        if(storage())discover(now,null,null);
    }
    private void discover(Instant now,Long patient,LocalDate requested) {
        Map<Long,Integer> used=new HashMap<>();
        Set<Long> visited=new HashSet<>();
        for(String lane:LANES) {
            String key=(patient==null?"GLOBAL":patient.toString())+":"+lane;
            Cursor cursor=(patient==null?globalCursors:cursors).computeIfAbsent(key,k->new Cursor());
            String scope=patient==null?"":" AND patient_id=?";
            if(cursor.high==0) {
                Map<String,Object> high=patient==null?repo.one("SELECT id FROM medication_reconciliation WHERE state=? ORDER BY id DESC LIMIT 1",lane):repo.one("SELECT id FROM medication_reconciliation WHERE state=? AND patient_id=? ORDER BY id DESC LIMIT 1",lane,patient);
                if(high==null)continue;
                cursor.high=id(high.get("id"));
                cursor.after=0;
            }
            List<Map<String,Object>> candidates=patient==null?repo.rows("SELECT id FROM medication_reconciliation WHERE state=? AND id>? AND id<=? ORDER BY id LIMIT 50",lane,cursor.after,cursor.high):repo.rows("SELECT id FROM medication_reconciliation WHERE state=?"+scope+" AND id>? AND id<=? ORDER BY id LIMIT 50",lane,patient,cursor.after,cursor.high);
            for(Map<String,Object> row:candidates) {
                long proposal=id(row.get("id"));
                cursor.after=proposal;
                if(!visited.add(proposal))continue;
                try {
                    Map<String,Object> p=repo.reconciliation(proposal);
                    if(p==null||closedComplete(p,now,requested))continue;
                    long execution=id(p.get("execution_id"));
                    int budget=7-used.getOrDefault(execution,0);
                    int advanced=materialize(execution,proposal,lane,now,requested,budget);
                    used.put(execution,used.getOrDefault(execution,0)+advanced);
                }
                catch(RuntimeException failure) {
                    log.warn("Medication interval candidate {} deferred: {}",proposal,failure instanceof WorkflowException?((WorkflowException)failure).getErrorCode():failure.getClass().getSimpleName());
                }
            }
            if(candidates.size()<50||cursor.after>=cursor.high) {
                cursor.after=0;
                cursor.high=0;
            }
        }
    }
    /** Closed business identity is immutable; this read-only skip cannot authorize a mutation. */
    private boolean closedComplete(Map<String,Object> p,Instant now,LocalDate requested) {
        if(!Arrays.asList("ENDED","SUPERSEDED").contains(p.get("state"))||p.get("applied_at")==null||p.get("scheduled_revision_id")==null)return false;
        Map<String,Object> v=repo.revision(id(p.get("scheduled_revision_id")));
        StructuredMapping m=parseMapping(object((String)v.get("mapping_json")));
        if("STOP".equals(m.action))return true;
        if(p.get("generated_through")==null)return false;
        Instant upper=at(p.get("ended_at"));
        if(upper==null)return false;
        if(m.endAt!=null&&m.endAt.isBefore(upper))upper=m.endAt;
        LocalDate through=upper.minusNanos(1).atZone(ZoneId.of(m.scheduleZone)).toLocalDate();
        LocalDate today=now.atZone(ZoneId.of(m.scheduleZone)).toLocalDate();
        if(today.isBefore(through))through=today;
        if(requested!=null&&requested.isBefore(through))through=requested;
        return !LocalDate.parse(p.get("generated_through").toString()).isBefore(through);
    }
    private int materialize(long execution,long proposal,String lane,Instant now,LocalDate requested,int budget) {
        Integer result=transaction().execute(tx-> {
            Map<String,Object> e=repo.execution(execution);
            if(e==null)return 0;
            long patient=id(e.get("patient_id"));
            // All current/pending and explicitly historical source identities are collected before any domain lock.
            MedicationExecutionRepository.IntervalAdmission admission=repo.lockGeneration(auth.owner(patient),patient,id(e.get("medication_id")),proposal);
            try {
                Map<String,Object> p=repo.reconciliation(proposal);
                e=repo.execution(execution);
                if(p==null||id(p.get("execution_id"))!=execution||!lane.equals(p.get("state")))return 0;
                if("SCHEDULED".equals(lane)) {
                    if(!Objects.equals(e.get("pending_reconciliation_id"),p.get("id")))return 0;
                    boundary.applyDueAdmitted(execution,now,admission);
                }
                boundary.endDueAdmitted(execution,now,admission);
                p=repo.reconciliation(proposal);
                e=repo.execution(execution);
                if(budget<=0||p.get("applied_at")==null||p.get("scheduled_revision_id")==null||!Arrays.asList("ACTIVE","ENDED","SUPERSEDED").contains(p.get("state")))return 0;
                Map<String,Object> v=repo.revision(id(p.get("scheduled_revision_id")));
                StructuredMapping mapping=parseMapping(object((String)v.get("mapping_json")));
                if(!"CHANGE".equals(mapping.action))return 0;
                ZoneId zone=ZoneId.of(mapping.scheduleZone);
                Instant upper=mapping.endAt,closed=at(p.get("ended_at"));
                if(closed!=null&&(upper==null||closed.isBefore(upper)))upper=closed;
                LocalDate through=now.atZone(zone).toLocalDate();
                if(requested!=null&&requested.isBefore(through))through=requested;
                if(upper!=null) {
                    LocalDate last=upper.minusNanos(1).atZone(zone).toLocalDate();
                    if(last.isBefore(through))through=last;
                }
                LocalDate date=p.get("generated_through")==null?mapping.effectiveLocal.toLocalDate():LocalDate.parse(p.get("generated_through").toString()).plusDays(1);
                List<Map<String,Object>> reminders=repo.rows("SELECT * FROM medication_reminder WHERE execution_id=? AND reconciliation_id=? ORDER BY id LIMIT 25",execution,proposal);
                if(reminders.size()>24)throw MedicationExecutionRepository.admissionLimit();
                Set<String> actualTimes=new TreeSet<>();
                for(Map<String,Object> reminder:reminders)actualTimes.add((String)reminder.get("remind_time"));
                if(reminders.size()!=mapping.times.size()||!actualTimes.equals(new TreeSet<>(mapping.times)))throw MedicationExecutionRepository.conflict("REMINDER_SET_CHANGED");
                boolean historical=!"ACTIVE".equals(p.get("state"));
                int advanced=0;
                for(; advanced<budget&&!date.isAfter(through); advanced++,date=date.plusDays(1)) {
                    admission.check(execution);
                    if(mapping.weekdays.contains(date.getDayOfWeek().getValue())) {
                        boolean unresolved=false;
                        for(String time:mapping.times)if(zone.getRules().getValidOffsets(date.atTime(LocalTime.parse(time))).size()!=1)unresolved=true;
                        if(unresolved) {
                            // The date is represented by explicit unknown-time evidence, never a guessed instant.
                            repo.update("UPDATE medication_execution SET review_required=1,lock_version=lock_version+1,updated_at=? WHERE id=?",now,execution);
                            events.appendSystem(patient,execution,id(repo.execution(execution).get("lock_version")),((Number)p.get("proposal_no")).intValue(),"TIME_UNRESOLVED",proposal,id(v.get("id")),"DST_EXCEPTION_REQUIRES_VERIFIED_REVISION",map("localDate",date.toString(),"scheduleZone",mapping.scheduleZone),null,null,now);
                        }
                        else for(Map<String,Object> reminder:reminders) {
                            LocalDateTime local=date.atTime(LocalTime.parse((String)reminder.get("remind_time")));
                            ZoneOffset offset=zone.getRules().getValidOffsets(local).get(0);
                            Instant slot=local.toInstant(offset);
                            if(slot.isBefore(mapping.effectiveAt)||upper!=null&&!slot.isBefore(upper))continue;
                            // Includes CANCELLED slots: a replacement never resurrects them.
                            if(repo.one("SELECT id FROM medication_intake WHERE reminder_id=? AND scheduled_at_utc=?",reminder.get("id"),slot)!=null)continue;
                            // Creation after the existing one-hour expiry is history, even while its interval is ACTIVE.
                            // Preserve unconfirmed evidence and explicit contact/fact recording, never catch-up-dose work.
                            // ACTIVE 晚生成随访保留真实计划 UTC 作为结束等待依据，不补服或生成实际事实。
                            boolean expired=historical||!slot.plusSeconds(3600).isAfter(now);
                            String status=expired?"MISSED":"PENDING";
                            repo.insert("INSERT INTO medication_intake(user_id,patient_id,reminder_id,medication_id,scheduled_at,status,dosage,workflow_version,execution_id,reconciliation_id,provenance,planned_quantity,planned_unit,schedule_zone,scheduled_at_utc,schedule_offset_seconds,follow_up_state,follow_up_kind,follow_up_cycle,follow_up_due_at) VALUES(?,?,?,?,?,?,?,1,?,?,'VERIFIED_ORDER',?,?,?,?,?,?,?,1,?)",reminder.get("user_id"),patient,reminder.get("id"),e.get("medication_id"),local,status,reminder.get("dosage"),execution,proposal,reminder.get("planned_quantity"),reminder.get("planned_unit"),mapping.scheduleZone,slot,offset.getTotalSeconds(),historical?"NOT_APPLICABLE":"OPEN",expired?"FOLLOW_UP_ONLY":"DOSE_REMINDER",historical?null:slot);
                        }
                    }
                    // Closed proposal business bytes, including updated_at, remain immutable.
                    repo.update("UPDATE medication_reconciliation SET generated_through=? WHERE id=?",date,proposal);
                    if(Objects.equals(e.get("current_reconciliation_id"),p.get("id")))repo.update("UPDATE medication_execution SET generated_through=? WHERE id=?",date,execution);
                }
                admission.check(execution);
                return advanced;
            }
            finally {
                admission.finish();
            }
        });
        return result==null?0:result;
    }
    public synchronized void processDueIntakes(Instant now) {
        if(!storage())return;
        if(dueHigh==null) {
            Map<String,Object> high=repo.one("SELECT id,follow_up_due_at FROM medication_intake WHERE workflow_version=1 AND follow_up_state='OPEN' AND follow_up_due_at<=? ORDER BY follow_up_due_at DESC,id DESC LIMIT 1",now);
            if(high==null)return;
            dueHigh=at(high.get("follow_up_due_at"));
            dueHighId=id(high.get("id"));
            dueAfter=null;
            dueAfterId=0;
        }
        String after=dueAfter==null?"":" AND (follow_up_due_at>? OR (follow_up_due_at=? AND id>?))";
        List<Object> args=new ArrayList<>(Arrays.asList(now,dueHigh,dueHigh,dueHighId));
        if(dueAfter!=null)args.addAll(Arrays.asList(dueAfter,dueAfter,dueAfterId));
        List<Map<String,Object>> rows=repo.rows("SELECT id,execution_id,follow_up_due_at FROM medication_intake WHERE workflow_version=1 AND follow_up_state='OPEN' AND follow_up_due_at<=? AND (follow_up_due_at<? OR (follow_up_due_at=? AND id<=?))"+after+" ORDER BY follow_up_due_at,id LIMIT 100",args.toArray());
        for(Map<String,Object> row:rows) {
            dueAfter=at(row.get("follow_up_due_at"));
            dueAfterId=id(row.get("id"));
            try {
                transaction().execute(tx-> {
                    processIntake(id(row.get("id")),now);
                    return null;
                });
            }
            catch(RuntimeException failure) {
                log.warn("Medication follow-up candidate {} deferred: {}",row.get("id"),failure instanceof WorkflowException?((WorkflowException)failure).getErrorCode():failure.getClass().getSimpleName());
            }
        }
        if(rows.size()<100||dueAfter!=null&&dueAfter.equals(dueHigh)&&dueAfterId>=dueHighId) {
            dueHigh=null;
            dueAfter=null;
        }
    }
    private void processIntake(long intake,Instant now) {
        Map<String,Object> first=repo.one("SELECT patient_id,medication_id,execution_id FROM medication_intake WHERE id=?",intake);
        if(first==null||first.get("execution_id")==null)return;
        long patient=id(first.get("patient_id")),owner=auth.owner(patient);
        repo.lockIntake(owner,patient,id(first.get("medication_id")),null,intake,null,null);
        Map<String,Object> i=repo.one("SELECT * FROM medication_intake WHERE id=?",intake);
        if(i==null||!Objects.equals(first.get("execution_id"),i.get("execution_id"))||!"OPEN".equals(i.get("follow_up_state")))return;
        Instant due=at(i.get("follow_up_due_at"));
        if(due==null||due.isAfter(now))return;
        if("DOSE_REMINDER".equals(i.get("follow_up_kind"))&&Arrays.asList("PENDING","SNOOZED").contains(i.get("status"))&&!due.plusSeconds(3600).isAfter(now))repo.update("UPDATE medication_intake SET status='MISSED',updated_at=? WHERE id=?",now,intake);
        long execution=id(i.get("execution_id"));
        String key="MEDICATION_EXECUTION:"+execution+":"+intake+":"+i.get("follow_up_cycle")+":"+java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC).format(due);
        if(repo.one("SELECT id FROM medication_execution_event WHERE due_key=?",key)!=null)return;
        repo.update("UPDATE medication_execution SET lock_version=lock_version+1,updated_at=? WHERE id=?",now,execution);
        Long proposal=i.get("reconciliation_id")==null?null:id(i.get("reconciliation_id")),revision=proposal==null?null:id(repo.reconciliation(proposal).get("scheduled_revision_id"));
        events.appendSystem(patient,execution,id(repo.execution(execution).get("lock_version")),((Number)i.get("follow_up_cycle")).intValue(),"DUE",proposal,revision,null,map("followUpKind",i.get("follow_up_kind"),"unconfirmed",true),intake,due,now);
    }
    private TransactionTemplate transaction() {
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }
    private boolean storage() {
        HouseholdWorkflowSchema.StorageReadiness s=repo.inspectStorageMetadata();
        if(s.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return false;
        s.requireStructurallyReady();
        return true;
    }
}
