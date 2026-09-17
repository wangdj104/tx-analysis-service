package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
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
    @Autowired private HealthEventMapper events;
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
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Start Datecannotlater thanEnd Date");
        List<HealthEvent> result = new ArrayList<>(events.selectList(dated(patientId, "event_date", from, to, limit)));
        for (BpSelfMonitorRecord r : measurements.selectList(dated(patientId, "record_date", from, to, limit))) {
            List<String> values = new ArrayList<>();
            if (r.getSystolicBp() != null || r.getDiastolicBp() != null) values.add("Blood Pressure " + text(r.getSystolicBp()) + "/" + text(r.getDiastolicBp()) + " mmHg");
            if (r.getBloodGlucose() != null) values.add("Blood Glucose " + r.getBloodGlucose() + " " + text(r.getBgUnit()));
            result.add(event(r.getId(), patientId, "MEASUREMENT", "Blood Pressure & Glucoserecord", r.getRecordDate(), text(r.getRecordTime()), String.join("; ", values)));
        }
        for (DialysisRecord r : dialysis.selectList(dated(patientId, "record_date", from, to, limit))) {
            result.add(event(r.getId(), patientId, "DIALYSIS", "Dialysis Records", r.getRecordDate(), "", "Pre-dialysis Weight " + text(r.getOnWeight()) + " kg; Post-dialysis Weight " + text(r.getOffWeight()) + " kg"));
        }
        Map<Long, String> names = new HashMap<>();
        for (Medication r : medications.selectList(new QueryWrapper<Medication>().eq("patient_id", patientId))) names.put(r.getId(), r.getDrugName());
        for (MedicationIntake r : intakes.selectList(this.<MedicationIntake>dated(patientId, "action_at", from, to, limit).eq("status", "TAKEN"))) {
            if (r.getActionAt() == null) continue;
            result.add(event(r.getId(), patientId, "INTAKE", "Taken: " + names.getOrDefault(r.getMedicationId(), "DeletedMedication"), r.getActionAt().toLocalDate(), r.getActionAt().toLocalTime().toString(), text(r.getDosage())));
        }
        for (MedicationLog r : logs.selectList(dated(patientId, "administration_time", from, to, limit))) {
            if (r.getAdministrationTime() == null) continue;
            result.add(event(r.getId(), patientId, "MEDICATION_LOG", "medicationrecord: " + names.getOrDefault(r.getMedicationId(), "DeletedMedication"), r.getAdministrationTime().toLocalDate(), r.getAdministrationTime().toLocalTime().toString(), text(r.getDosage())));
        }
        result.sort(Comparator.comparing(HealthEvent::getEventDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(e -> text(e.getEventTime()), Comparator.reverseOrder())
                .thenComparing(HealthEvent::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        return result.stream().limit(limit).collect(Collectors.toList());
    }

    private HealthEvent event(Long id, Long patientId, String type, String title, LocalDate date, String time, String summary) {
        HealthEvent e = new HealthEvent(); e.setId(id); e.setSourceId(id); e.setPatientId(patientId);
        e.setSourceType(type); e.setEventType(type); e.setTitle(title); e.setEventDate(date); e.setEventTime(time); e.setSummary(summary);
        return e;
    }
    private String text(Object value) { return value == null ? "" : value.toString(); }
}
