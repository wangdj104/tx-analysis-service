package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.familyassistance.FamilyAssistanceSourceBridge;
import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** One bounded, typed native appointment command. No clinical completion or implicit recurrence. */
@Service
public class AppointmentFollowUpService {
    private final VisitFollowUpRepository repo;
    private final VisitFollowUpAdmission admission;
    private final VisitFollowUpProperties properties;
    private final FamilyAssistanceSourceBridge assistance;
    public AppointmentFollowUpService(VisitFollowUpRepository repo,VisitFollowUpAdmission admission,
            VisitFollowUpProperties properties,FamilyAssistanceSourceBridge assistance) {
        this.repo=Objects.requireNonNull(repo); this.admission=Objects.requireNonNull(admission);
        this.properties=Objects.requireNonNull(properties); this.assistance=Objects.requireNonNull(assistance);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,String rawJson) {
        return command(actor,parseJson(rawJson,properties.getClock()));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,Command c) {
        Objects.requireNonNull(c);
        if (!c.getSourceKind().isAppointment()) throw invalid("An appointment command is required.");
        VisitFollowUpAdmission.Context context=admission.lockAppointmentOrVisitSource(actor,c);
        return VisitFollowUpCommandStore.executeAppointment(actor,c,context,()->change(actor,c,context));
    }
    private Map<String,Object> change(long actor,Command c,VisitFollowUpAdmission.Context context) {
        NativeSource source=repo.source(c.getSource());
        Map<String,Object> state=repo.state(c.getSource()), body=c.getBody();
        long workflow;
        if(c.getOperation()==Operation.TRACK_APPOINTMENT) {
            if(state!=null)throw changed();
            requireNativeShape(source);
            workflow=repo.insertState(context,c.getSource(),responsibility(body));
            long event=append(context,workflow,map("source_revision",1L,"last_native_event_id","SELF"),null,map("automaticRecurrenceStopped",true));
            return result(c,workflow,event,null);
        }
        if(state==null || !source.getFingerprint().equals(state.get("source_fingerprint")))throw changed();
        workflow=id(state.get("id"));
        if(Arrays.asList(Operation.CREATE_NEXT,Operation.LINK_NEXT,Operation.REPLACE_NEXT).contains(c.getOperation()))
            return successor(actor,c,context,source,state);
        Map<String,Object> patch=new LinkedHashMap<>();
        String reason=(String)body.get("reason");
        if(c.getOperation()==Operation.SET_NEXT_DECISION) {
            patch.putAll(responsibility(body)); patch.put("next_decision",body.get("nextDecision")); unlink(patch);
        } else {
            Long eventId=nullable(state.get("current_fact_event_id"));
            Map<String,Object> currentFact=eventId==null?null:repo.event(eventId);
            boolean selected=Objects.equals(source.getDoctorId(),actor)&&context.scope().currentAuthority().isActiveDoctor(actor);
            if(c.getOperation()==Operation.CORRECT_ATTENDANCE && (eventId==null || !eventId.equals(body.get("eventId"))))throw changed();
            Map<String,Object> transition=AppointmentFollowUpTransitions.decide(c.getSourceKind().name(),String.valueOf(source.getValues().get("status")),
                    String.valueOf(state.get("attendance")),String.valueOf(state.get("attendance_origin")),eventId!=null,
                    c.getOperation().name(),(String)body.get("attendance"),selected,currentFact!=null&&number(currentFact.get("actor_id"))==actor);
            Map<String,Object> target=source.nativeTarget(); target.put("native_status",transition.get("nativeStatus"));
            if(c.getOperation()==Operation.RESCHEDULE) {
                Map<String,Object> schedule=object(body.get("schedule"));
                for(String field:Arrays.asList("appointmentType","consultationMode")) {
                    String nativeField="appointmentType".equals(field)?"appointment_type":"consultation_mode";
                    if(schedule.containsKey(field)&&!Objects.equals(schedule.get(field),source.getValues().get(nativeField)))
                        throw invalid("Rescheduling changes the time and selected clinician only.");
                }
                target=scheduleTarget(source,schedule);
                requireSchedule(context,source.getReference(),target,false);
                requireLocalOrder(context,source.getReference(),(LocalDateTime)target.get("native_start_wall"));
            }
            if(c.getOperation()==Operation.CANCEL && state.get("successor_kind")!=null) {
                if(body.get("successorDisposition")==null)throw invalid("Choose whether to keep or unlink the next appointment.");
                if("UNLINK".equals(body.get("successorDisposition"))) {unlink(patch);patch.put("next_decision","NEEDS_DECISION");}
            }
            if(Arrays.asList(Operation.RECORD_ATTENDANCE,Operation.CORRECT_ATTENDANCE,Operation.COMPLETE_SELECTED_APPOINTMENT).contains(c.getOperation())) {
                if("NO_SHOW".equals(transition.get("attendance"))) {
                    LocalDateTime start=(LocalDateTime)source.nativeTarget().get("native_start_wall");
                    if(start==null || LocalDateTime.ofInstant(properties.now(),ZoneId.systemDefault()).isBefore(start))throw new WorkflowException(409,"APPOINTMENT_NOT_STARTED","A no-show cannot be recorded before the appointment starts.");
                }
                ActualTime actual=actual(object(body.get("actual")),properties.getClock());
                patch.put("attendance",transition.get("attendance")); patch.put("attendance_origin",transition.get("origin"));
                patch.put("actual_time_kind",actual.getTimeKind().name()); patch.put("actual_at_utc",actual.getOccurredAt()); patch.put("actual_offset",actual.getInputOffset());
                patch.put("current_fact_event_id","UNVERIFIED".equals(transition.get("attendance"))?null:"SELF");
                if(c.getOperation()==Operation.CORRECT_ATTENDANCE && Arrays.asList("OPEN","BOOKED").contains(target.get("native_status"))) {
                    requireSchedule(context,source.getReference(),target,true);
                    requireLocalOrder(context,source.getReference(),(LocalDateTime)target.get("native_start_wall"));
                }
            }
            NativeSource after=repo.appointmentTarget(context,c.getSource(),target);
            boolean nativeChanged=!source.getFingerprint().equals(after.getFingerprint());
            if(nativeChanged) {
                patch.putAll(after.nativeTarget()); patch.put("source_fingerprint",after.getFingerprint());
                patch.put("source_revision",nextVersion(number(state.get("source_revision")))); patch.put("last_native_event_id","SELF");
            }
            long event=append(context,workflow,patch,reason,c.getOperation()==Operation.CORRECT_ATTENDANCE?map("correctedEventId",eventId):null);
            if(nativeChanged) {
                boolean clear=c.getOperation()==Operation.RESCHEDULE || c.getOperation()==Operation.CORRECT_ATTENDANCE&&Arrays.asList("OPEN","BOOKED").contains(target.get("native_status"));
                repo.writeAppointmentTarget(context,c.getSource(),clear);
                assistance.appointmentChangedLocked(c.getPatientId(),c.getSourceId(),c.getSourceKind()==SourceKind.CARE_ITEM_APPOINTMENT?"CARE_ITEM":"CARE_APPOINTMENT");
            }
            return result(c,workflow,event,null);
        }
        long event=append(context,workflow,patch,reason,null);
        return result(c,workflow,event,null);
    }
    private Map<String,Object> successor(long actor,Command c,VisitFollowUpAdmission.Context context,NativeSource source,Map<String,Object> state) {
        Map<String,Object> body=c.getBody();boolean replace=c.getOperation()==Operation.REPLACE_NEXT;
        if(!replace&&state.get("successor_kind")!=null)throw new WorkflowException(409,"APPOINTMENT_SUCCESSOR_EXISTS","Compare the existing next appointment and explicitly replace it.");
        if(replace&&state.get("successor_kind")==null)throw changed();
        SourceRef target;Map<String,Object> targetState;NativeSource nativeTarget;
        if(body.get("schedule")!=null) {
            Map<String,Object> schedule=object(body.get("schedule"));
            Map<String,Object> plan=scheduleTarget(source,schedule);
            plan.put("native_status",source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"OPEN":"BOOKED");
            requireSchedule(context,null,plan,false);
            requireLater((LocalDateTime)source.nativeTarget().get("native_start_wall"),(LocalDateTime)plan.get("native_start_wall"));
            requireLocalOrder(context,source.getReference(),(LocalDateTime)source.nativeTarget().get("native_start_wall"));
            CreatedSource created=repo.insertCreatedSource(context); target=created.getReference();
            long targetId=repo.insertCreatedState(context,created);
            append(context,targetId,map("source_revision",1L,"last_native_event_id","SELF"),(String)body.get("reason"),map("explicitSuccessor",true));
            nativeTarget=repo.source(target);targetState=repo.state(target);
        } else {
            Map<String,Object> binding=object(body.get("successor"));
            target=new SourceRef(SourceKind.valueOf(String.valueOf(binding.get("sourceKind"))),id(binding.get("sourceId")));
            requireAdmitted(context,target);nativeTarget=repo.source(target);targetState=repo.state(target);
            if(!nativeTarget.getFingerprint().equals(binding.get("expectedSourceFingerprint"))||number(targetState==null?null:targetState.get("lock_version"))!=number(binding.get("expectedVersion")))throw changed();
            if(nativeTarget.getPatientId()!=c.getPatientId()||target.getKind()!=source.getReference().getKind()||target.equals(source.getReference()))throw changed();
            if(!Arrays.asList("OPEN","BOOKED").contains(nativeTarget.getValues().get("status")) || targetState!=null&&!"UNVERIFIED".equals(targetState.get("attendance")))throw changed();
            requireLater((LocalDateTime)source.nativeTarget().get("native_start_wall"),(LocalDateTime)nativeTarget.nativeTarget().get("native_start_wall"));
            requireLocalOrder(context,source.getReference(),(LocalDateTime)source.nativeTarget().get("native_start_wall"));
            requireLocalOrder(context,target,(LocalDateTime)nativeTarget.nativeTarget().get("native_start_wall"));
            for(Map<String,Object> predecessor:repo.predecessor(target))if(!reference(predecessor).equals(source.getReference()))throw new WorkflowException(409,"APPOINTMENT_SUCCESSOR_CLAIMED","That appointment already has a predecessor.");
            if(targetState==null) {
                requireNativeShape(nativeTarget);long targetId=repo.insertState(context,target,responsibility(body));
                append(context,targetId,map("source_revision",1L,"last_native_event_id","SELF"),(String)body.get("reason"),map("explicitManagement",true));
                targetState=repo.state(target);
            }else if(!nativeTarget.getFingerprint().equals(targetState.get("source_fingerprint")))throw changed();
        }
        Map<String,Object> patch=responsibility(body);patch.put("next_decision","SCHEDULED");patch.put("successor_kind",target.getKind().name());
        patch.put("successor_care_item_id",target.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?target.getId():null);
        patch.put("successor_care_appointment_id",target.getKind()==SourceKind.CARE_APPOINTMENT?target.getId():null);
        patch.put("successor_bound_version",targetState.get("lock_version"));patch.put("successor_bound_fingerprint",nativeTarget.getFingerprint());
        Map<String,Object> details=map("previousSuccessorKind",state.get("successor_kind"),"previousSuccessorId",state.get(state.get("successor_care_item_id")!=null?"successor_care_item_id":"successor_care_appointment_id"),"previousAppointmentKept",replace);
        long event=append(context,id(state.get("id")),patch,(String)body.get("reason"),details);
        return result(c,id(state.get("id")),event,target);
    }
    private void requireSchedule(VisitFollowUpAdmission.Context context,SourceRef source,Map<String,Object> target,boolean restoration) {
        LocalDateTime start=(LocalDateTime)target.get("native_start_wall"),end=(LocalDateTime)target.get("native_end_wall");
        if(start==null)throw slotChanged();
        Long doctor=nullable(target.get("native_doctor_id"));
        if(doctor==null) {LocalDateTime notify=(LocalDateTime)target.get("native_notify_wall");if(notify!=null&&notify.isAfter(start))throw slotChanged();return;}
        if(end==null||!end.isAfter(start)||!context.scope().subjects.contains(doctor))throw slotChanged();
        VisitFollowUpAuthorizationService.Snapshot auth=context.scope().currentAuthority();
        if(!auth.isActiveDoctor(doctor)||restoration&&!auth.canAppointmentWrite(doctor))throw slotChanged();
        Long schedule=nullable(target.get("native_schedule_id"));
        if(schedule!=null) {
            Map<String,Object> row=repo.one("SELECT id,doctor_user_id,work_date,start_time,end_time,status FROM doctor_schedule WHERE id=?",schedule);
            if(row==null||number(row.get("doctor_user_id"))!=doctor||!"AVAILABLE".equals(row.get("status")))throw slotChanged();
            LocalDate date=LocalDate.parse(String.valueOf(row.get("work_date")));
            if(start.isBefore(LocalDateTime.of(date,LocalTime.parse(String.valueOf(row.get("start_time")))))||end.isAfter(LocalDateTime.of(date,LocalTime.parse(String.valueOf(row.get("end_time"))))))throw slotChanged();
        }
        long except=source==null?0:source.getId();
        if(!repo.rows("SELECT id FROM care_appointment WHERE doctor_user_id=? AND status='BOOKED' AND start_at<? AND end_at>? AND id<>? LIMIT 1",1,doctor,end,start,except).isEmpty())throw slotChanged();
    }
    private void requireLocalOrder(VisitFollowUpAdmission.Context context,SourceRef source,LocalDateTime start) {
        requireAdmitted(context,source);
        for(Map<String,Object> previous:repo.predecessor(source)) {
            SourceRef ref=reference(previous);requireAdmitted(context,ref);
            requireLater((LocalDateTime)repo.source(ref).nativeTarget().get("native_start_wall"),start);
        }
        Map<String,Object> state=repo.state(source);
        if(state!=null&&state.get("successor_kind")!=null) {
            SourceRef ref=new SourceRef(SourceKind.valueOf(String.valueOf(state.get("successor_kind"))),id(state.get(source.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id")));
            requireAdmitted(context,ref);requireLater(start,(LocalDateTime)repo.source(ref).nativeTarget().get("native_start_wall"));
        }
    }
    private void requireAdmitted(VisitFollowUpAdmission.Context c,SourceRef source) {if(!c.scope().sources.contains(source)||!c.scope().contains(source.getKind().tableName(),source.getId()))throw VisitFollowUpAuthorizationService.changed();}
    private static void requireLater(LocalDateTime before,LocalDateTime after) {if(before==null||after==null||!after.isAfter(before))throw new WorkflowException(409,"APPOINTMENT_ORDER_CHANGED","The next appointment must remain strictly after its predecessor.");}
    private static void requireNativeShape(NativeSource source) {
        String status=String.valueOf(source.getValues().get("status"));
        if(!Arrays.asList(source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"OPEN":"BOOKED",source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"DONE":"COMPLETED","NO_SHOW","CANCELLED").contains(status))throw changed();
        Map<String,Object> target=source.nativeTarget();
        if(source.getReference().getKind()==SourceKind.CARE_APPOINTMENT && (target.get("native_start_wall")==null||target.get("native_end_wall")==null||!((LocalDateTime)target.get("native_end_wall")).isAfter((LocalDateTime)target.get("native_start_wall"))))throw slotChanged();
    }
    private static Map<String,Object> scheduleTarget(NativeSource source,Map<String,Object> schedule) {
        Map<String,Object> target=source.nativeTarget();boolean legacy=source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT;
        target.put("native_start_wall",wallTime(schedule.get(legacy?"eventAt":"startAt")));
        if(legacy)target.put("native_notify_wall",schedule.get("notifyAt")==null?null:wallTime(schedule.get("notifyAt")));
        else {target.put("native_end_wall",wallTime(schedule.get("endAt")));target.put("native_doctor_id",schedule.get("doctorUserId"));target.put("native_schedule_id",schedule.get("scheduleId"));}
        return target;
    }
    private long append(VisitFollowUpAdmission.Context context,long workflow,Map<String,Object> patch,String reason,Map<String,Object> details) {
        long event=repo.appendAppointmentEvent(context,workflow,patch,reason,details);repo.advanceAppointmentState(context,workflow,event);return event;
    }
    private static Map<String,Object> result(Command c,long workflow,long event,SourceRef successor) {
        Map<String,Object> result=map("workflowId",workflow,"sourceKind",c.getSourceKind().name(),"sourceId",c.getSourceId(),"version",nextVersion(c.getExpectedVersion()),"eventId",event,"operation",c.getOperation().name());
        if(successor!=null){result.put("successorKind",successor.getKind().name());result.put("successorId",successor.getId());}return result;
    }
    private static Map<String,Object> responsibility(Map<String,Object> body) {return map("responsible_user_id",body.get("responsibleUserId"),"decision_due_at",Instant.parse(String.valueOf(body.get("dueAt"))));}
    private static void unlink(Map<String,Object> patch) {for(String key:Arrays.asList("successor_kind","successor_care_item_id","successor_care_appointment_id","successor_bound_version","successor_bound_fingerprint"))patch.put(key,null);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return(Map<String,Object>)value;}
    private static WorkflowException changed(){return new WorkflowException(409,"SOURCE_CHANGED","The appointment changed. Refresh and compare before making a new decision.");}
    private static WorkflowException slotChanged(){return new WorkflowException(409,"APPOINTMENT_SLOT_CHANGED","The original clinician or time slot is no longer available. Keep this record and explicitly arrange the next appointment.");}
}
