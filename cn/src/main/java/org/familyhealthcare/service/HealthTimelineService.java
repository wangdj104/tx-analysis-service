package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.service.careplan.CarePlanTimelineProjector;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.service.measurementread.OriginalMeasurementReadAuthorization;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionAuthorizationService;
import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Combine source records when reading so edits and deletions are reflected without duplicate events. */
@Service
public class HealthTimelineService {
    @Autowired private DataScopeHelper scope;
    @Autowired private OriginalMeasurementReadAuthorization measurementAccess;
    @Autowired private MedicationExecutionAuthorizationService medicationAccess;
    @Autowired private HealthTimelineFacts facts;
    @Autowired private CareItemMapper careItems;
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService familyAccess;
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceQueryService familyQueries;
    @Autowired private org.familyhealthcare.service.careplan.CarePlanAuthorizationService planAccess;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard medicationTracking;
    @Autowired private HealthEventMapper events;
    @Autowired(required=false) private CarePlanTimelineProjector carePlans;
    @Autowired private BpSelfMonitorRecordMapper measurements;
    @Autowired private DialysisRecordMapper dialysis;
    @Autowired private MedicationIntakeMapper intakes;
    @Autowired private MedicationLogMapper logs;
    @Autowired private MedicationMapper medications;

    private <T> QueryWrapper<T> dated(Long patientId, String column, LocalDate from, LocalDate to, int limit) {
        QueryWrapper<T> q = new QueryWrapper<T>().eq("patient_id", patientId);
        if (from != null) q.ge(column, from);
        if (to != null) q.lt(column, to.plusDays(1).atStartOfDay());
        return q.orderByDesc(column).orderByDesc("id").last("limit " + limit);
    }

    public List<HealthEvent> list(Long patientId, LocalDate from, LocalDate to, int limit) {
        if(patientId==null||patientId<=0||limit<1||limit>200||from!=null&&to!=null&&from.isAfter(to)||to!=null&&to.getYear()>=9999)throw new IllegalArgumentException("Invalid timeline patient, date range or limit.");
        long actor=scope.requireUserId();medicationAccess.requireAccount(actor);
        scope.requirePatientAccess(patientId,null,false);
        List<Runnable> rechecks=new ArrayList<>();List<HealthEvent> result=new ArrayList<>();
        // Only authored manual entries belong to this table. Old copied summaries
        // cannot grant access to a deleted or newly restricted source.
        if(allowed(()->scope.requirePatientAccess(patientId,null,false))){
            result.addAll(events.selectList(this.<HealthEvent>dated(patientId,"event_date",from,to,limit).and(q->q.isNull("source_type").or().eq("source_type","MANUAL"))));
            rechecks.add(()->scope.requirePatientAccess(patientId,null,false));
        }
        if(carePlans!=null)try{result.addAll(carePlans.list(actor,patientId,from,to,limit));}
        catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;}
        if(result.stream().anyMatch(e->"CARE_PLAN_EVENT".equals(e.getSourceType())))rechecks.add(()->planAccess.requireRead(actor,patientId));
        boolean[] selfMode={false};
        if(allowed(()->selfMode[0]=measurementAccess.beginRaw(actor,patientId,true))){
          for (BpSelfMonitorRecord r : measurements.selectList(dated(patientId, "record_date", from, to, limit))) {
            List<String> values = new ArrayList<>();
            if (r.getSystolicBp() != null || r.getDiastolicBp() != null) values.add(org.familyhealthcare.util.ExportLocalization.text("Blood pressure ", "血压 ") + text(r.getSystolicBp()) + "/" + text(r.getDiastolicBp()) + " mmHg");
            if (r.getBloodGlucose() != null) values.add(org.familyhealthcare.util.ExportLocalization.text("Blood glucose ", "血糖 ") + r.getBloodGlucose() + " " + text(r.getBgUnit()));
            result.add(event(r.getId(), patientId, "MEASUREMENT", org.familyhealthcare.util.ExportLocalization.text("Blood pressure and glucose record", "血压血糖记录"), r.getRecordDate(), text(r.getRecordTime()), String.join("; ", values)));
          }
          rechecks.add(()->measurementAccess.finishRaw(actor,patientId,true,selfMode[0]));
        }
        boolean[] journeyMode={false};
        if(allowed(()->journeyMode[0]=measurementAccess.beginRaw(actor,patientId,false))){
            result.addAll(facts.measurements(patientId,from,to,limit));
            rechecks.add(()->measurementAccess.finishRaw(actor,patientId,false,journeyMode[0]));
        }
        if(allowed(()->scope.requirePatientAccess(patientId,"DIALYSIS",false))){
          for (DialysisRecord r : dialysis.selectList(dated(patientId, "record_date", from, to, limit))) {
            result.add(event(r.getId(), patientId, "DIALYSIS", org.familyhealthcare.util.ExportLocalization.text("Dialysis record", "透析记录"), r.getRecordDate(), "", org.familyhealthcare.util.ExportLocalization.text("Pre-dialysis weight ", "透前体重 ") + text(r.getOnWeight()) + org.familyhealthcare.util.ExportLocalization.text(" kg; Post-dialysis weight ", " kg；透后体重 ") + text(r.getOffWeight()) + " kg"));
          }
          rechecks.add(()->scope.requirePatientAccess(patientId,"DIALYSIS",false));
        }
        if(medicationAccess.canRead(actor,patientId)){
        List<MedicationIntake> intakeRows=intakes.selectList(this.<MedicationIntake>dated(patientId,"action_at",from,to,limit).in("status",Arrays.asList("TAKEN","SKIPPED")));
        List<MedicationLog> logRows=logs.selectList(dated(patientId,"administration_time",from,to,limit));
        // Empty legacy sources disclose nothing. Typed facts below still require
        // their own storage validation, and current module access is rechecked.
        if(!intakeRows.isEmpty()||!logRows.isEmpty()){
        org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy=medicationTracking.legacyRead(patientId);
        Map<Long, String> names = new HashMap<>();
        for (Medication r : medications.selectList(new QueryWrapper<Medication>().eq("patient_id", patientId))) names.put(r.getId(), r.getDrugName());
        for (MedicationIntake r : legacy.intakes(actor,intakeRows)) {
            if (r.getNextAction()!=null||r.getActionAt() == null) continue; // B 事实由 typed timeline 读取，不把旧本地时刻或剂量文本推断成已知事实。
            HealthEvent intake=event(r.getId(), patientId, "INTAKE", ("TAKEN".equals(r.getStatus())?org.familyhealthcare.util.ExportLocalization.text("Taken: ", "已服药："):org.familyhealthcare.util.ExportLocalization.text("Skipped: ", "已跳过：")) + names.getOrDefault(r.getMedicationId(), org.familyhealthcare.util.ExportLocalization.text("Deleted medication", "已删除药品")), r.getActionAt().toLocalDate(), r.getActionAt().toLocalTime().toString(), text(r.getDosage()));intake.setCurrentState(r.getStatus());result.add(intake);
        }
        for (MedicationLog r : legacy.logs(actor,logRows)) {
            if (r.getAdministrationTime() == null) continue;
            result.add(event(r.getId(), patientId, "MEDICATION_LOG", org.familyhealthcare.util.ExportLocalization.text("Medication record: ", "用药记录：") + names.getOrDefault(r.getMedicationId(), org.familyhealthcare.util.ExportLocalization.text("Deleted medication", "已删除药品")), r.getAdministrationTime().toLocalDate(), r.getAdministrationTime().toLocalTime().toString(), text(r.getDosage())));
        }
        rechecks.add(()->legacy.finish(actor));
        }
        rechecks.add(()->medicationAccess.requireRead(actor,patientId));
        }
        result.addAll(facts.list(actor,patientId,from,to,limit,rechecks));
        boolean ordinary=familyAccess.canRead(actor,patientId,"HANDOVER"),appointments=familyAccess.canRead(actor,patientId,"ACCOMPANIMENT");
        if(ordinary||appointments){
            List<String> kinds=new ArrayList<>();if(ordinary)kinds.add("HANDOVER");if(appointments)kinds.add("APPOINTMENT");
            List<CareItem> rows=careItems.selectList(this.<CareItem>dated(patientId,"event_at",from,to,limit).in("kind",kinds));
            for(CareItem row:rows.isEmpty()?Collections.<CareItem>emptyList():familyQueries.legacyItems(actor,patientId,rows)){
                if(row.getEventAt()==null)continue;
                HealthEvent fact=event(row.getId(),patientId,"FAMILY_RECORD",org.familyhealthcare.util.ExportLocalization.text("Family care record","家庭照护记录"),row.getEventAt().toLocalDate(),row.getEventAt().toLocalTime().toString(),row.getTitle());fact.setEventType(row.getKind());fact.setCurrentState(row.getStatus());result.add(fact);
            }
            if(ordinary)rechecks.add(()->familyAccess.requireRead(actor,patientId,"HANDOVER"));
            if(appointments)rechecks.add(()->familyAccess.requireRead(actor,patientId,"ACCOMPANIMENT"));
        }
        result.sort(Comparator.comparing(HealthEvent::getEventDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(e -> text(e.getEventTime()), Comparator.reverseOrder())
                .thenComparing(e->text(e.getSourceType()))
                .thenComparing(HealthEvent::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        for(Runnable check:rechecks)check.run();
        if(!Objects.equals(actor,CurrentUserUtil.getCurrentUserId()))throw new IllegalStateException("Access denied to this data.");
        medicationAccess.requireAccount(actor);scope.requirePatientAccess(patientId,null,false);
        return result.stream().limit(limit).collect(Collectors.toList());
    }

    private HealthEvent event(Long id, Long patientId, String type, String title, LocalDate date, String time, String summary) {
        HealthEvent e = new HealthEvent(); e.setId(id); e.setSourceId(id);e.setSourceRecordId(id); e.setPatientId(patientId);
        e.setSourceType(type); e.setEventType(type); e.setTitle(title); e.setEventDate(date); e.setEventTime(time); e.setSummary(summary);
        return e;
    }
    private String text(Object value) { return value == null ? "" : value.toString(); }
    private static boolean allowed(Runnable check){
        try{check.run();return true;}
        catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;return false;}
        catch(WorkflowException denied){if(denied.getStatus()!=403)throw denied;return false;}
        catch(IllegalStateException denied){return false;}
    }
}
