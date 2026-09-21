package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ClinicalWorkbenchService {
    @Autowired private DataScopeHelper scope;
    @Autowired private PatientMapper patients;
    @Autowired private PatientClinicalMapper clinical;
    @Autowired private PatientHealthTargetMapper targets;
    @Autowired private AlertRecordMapper alerts;
    @Autowired private MedicationIntakeMapper intakes;
    @Autowired private MedicationMapper medications;
    @Autowired private CareItemMapper careItems;
    @Autowired private MedicalRecordMapper medicalRecords;
    @Autowired private MedicalRecordItemMapper medicalItems;
    @Autowired private DialysisRecordMapper dialysisRecords;
    @Autowired private AiAnalysisRecordMapper analysisRecords;
    @Autowired private MedicationStockService stockService;
    @Autowired private JdbcTemplate jdbc;

    public Map<String, Object> overview(Long patientId) {
        scope.requirePatient(patientId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attention", attention(patientId));
        out.put("dataQuality", dataQuality(patientId));
        out.put("medicationSafety", medicationSafety(patientId));
        out.put("dialysisQuality", dialysisQuality(patientId, 90));
        out.put("emergencyCard", emergencyCard(patientId));
        out.put("generatedAt", LocalDateTime.now());
        return out;
    }

    public Map<String, Object> attention(Long patientId) {
        scope.requirePatient(patientId);
        List<Map<String, Object>> items = new ArrayList<>();
        List<AlertRecord> activeAlerts = alerts.selectList(new QueryWrapper<AlertRecord>()
                .eq("patient_id", patientId).and(q -> q.isNull("status").or().ne("status", "RESOLVED"))
                .orderByDesc("triggered_at").last("limit 100"));
        for (AlertRecord alert : activeAlerts) {
            Map<String, Object> item = attentionItem("ALERT", alert.getAlertLevel(), alert.getAlertTitle(),
                    alert.getTriggeredValue(), alert.getTriggeredAt(), "Review and document the clinical response",
                    "/health-analysis?tab=alert", alert.getId());
            item.put("status", alert.getStatus());
            item.put("occurrenceCount", alert.getOccurrenceCount());
            items.add(item);
        }

        LocalDateTime now = LocalDateTime.now();
        List<MedicationIntake> medicationTasks = intakes.selectList(new QueryWrapper<MedicationIntake>()
                .eq("patient_id", patientId).in("status", "PENDING", "MISSED", "SNOOZED")
                .le("scheduled_at", now.plusHours(12)).orderByAsc("scheduled_at").last("limit 100"));
        Map<Long, Medication> medicationById = medications.selectList(new QueryWrapper<Medication>().eq("patient_id", patientId))
                .stream().collect(Collectors.toMap(Medication::getId, Function.identity(), (a, b) -> a));
        for (MedicationIntake task : medicationTasks) {
            Medication medication = medicationById.get(task.getMedicationId());
            String severity = "MISSED".equals(task.getStatus()) ? "WARNING" : "INFO";
            items.add(attentionItem("MEDICATION", severity,
                    (medication == null ? "Medication" : medication.getDrugName()) + " · " + task.getStatus(),
                    task.getDosage(), task.getScheduledAt(), "Confirm taken, snooze, or record the reason",
                    "/family-health?tab=today", task.getId()));
        }

        for (Map<String, Object> row : stockService.list(patientId)) {
            if (!Boolean.TRUE.equals(row.get("low"))) continue;
            items.add(attentionItem("STOCK", "WARNING", "Low medication stock · " + row.get("drugName"),
                    "Remaining " + row.get("quantity") + " " + row.get("unit"), null,
                    "Replenish stock or revise the active prescription", "/care", row.get("id")));
        }

        for (CareItem care : careItems.selectList(new QueryWrapper<CareItem>().eq("patient_id", patientId).orderByAsc("event_at"))) {
            if (Arrays.asList("DONE", "COMPLETED", "CANCELLED", "RESOLVED").contains(care.getStatus())) continue;
            LocalDateTime due = care.getEventAt() != null ? care.getEventAt() : care.getNotifyAt();
            if (due == null || due.isAfter(now)) continue;
            Map<String, Object> item = attentionItem("CARE", "WARNING", care.getTitle(), care.getKind(), due,
                    "Complete, reassign, or document why it is deferred", "/care", care.getId());
            item.put("ownerId", care.getAssignedUserId() == null ? care.getUserId() : care.getAssignedUserId());
            items.add(item);
        }
        items.sort(Comparator.comparingInt(this::severityRank).thenComparing(row -> String.valueOf(row.get("dueAt"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        out.put("critical", items.stream().filter(x -> "CRITICAL".equals(x.get("severity"))).count());
        out.put("warning", items.stream().filter(x -> "WARNING".equals(x.get("severity"))).count());
        return out;
    }

    public Map<String, Object> dataQuality(Long patientId) {
        scope.requirePatient(patientId);
        List<Map<String, Object>> issues = new ArrayList<>();
        List<MedicalRecord> records = medicalRecords.selectList(new QueryWrapper<MedicalRecord>()
                .eq("patient_id", patientId).orderByDesc("record_date").last("limit 300"));
        Map<String, List<MedicalRecord>> externalIds = records.stream()
                .filter(r -> r.getSourceExternalId() != null && !r.getSourceExternalId().trim().isEmpty())
                .collect(Collectors.groupingBy(r -> r.getSourceType() + ":" + r.getSourceExternalId()));
        for (MedicalRecord record : records) {
            if ("REVIEW_REQUIRED".equals(record.getVerificationStatus())) {
                issues.add(issue("REVIEW", "WARNING", "Imported record needs review", recordLabel(record),
                        "medical-record", record.getId(), record.getConfidenceScore()));
            }
            if (record.getConfidenceScore() != null && record.getConfidenceScore().compareTo(new BigDecimal("0.80")) < 0) {
                issues.add(issue("LOW_CONFIDENCE", "WARNING", "Low-confidence recognition", recordLabel(record),
                        "medical-record", record.getId(), record.getConfidenceScore()));
            }
            if (record.getRecordDate() == null || record.getHospitalName() == null || record.getHospitalName().trim().isEmpty()) {
                issues.add(issue("MISSING_CONTEXT", "INFO", "Record context is incomplete",
                        recordLabel(record) + " · add date and facility", "medical-record", record.getId(), null));
            }
        }
        for (Map.Entry<String, List<MedicalRecord>> entry : externalIds.entrySet()) {
            if (entry.getValue().size() > 1) {
                issues.add(issue("DUPLICATE", "WARNING", "Possible duplicate imported records",
                        entry.getKey() + " appears " + entry.getValue().size() + " times", "medical-record",
                        entry.getValue().get(0).getId(), null));
            }
        }
        List<DialysisRecord> dialysis = dialysisRecords.selectList(new QueryWrapper<DialysisRecord>()
                .eq("patient_id", patientId).orderByDesc("record_date").last("limit 60"));
        for (DialysisRecord row : dialysis) {
            if ("INCOMPLETE".equals(row.getRecordType()) || row.getSessionMinutes() == null || row.getKtv() == null && row.getUrr() == null) {
                issues.add(issue("DIALYSIS_COMPLETENESS", "INFO", "Dialysis quality fields are incomplete",
                        String.valueOf(row.getRecordDate()) + " · add duration and Kt/V or URR when available",
                        "dialysis", row.getId(), null));
            }
        }
        List<AiAnalysisRecord> drafts = analysisRecords.selectList(new QueryWrapper<AiAnalysisRecord>()
                .eq("patient_id", patientId).eq("review_status", "REVIEW_REQUIRED").orderByDesc("created_at").last("limit 50"));
        for (AiAnalysisRecord draft : drafts) {
            issues.add(issue("AI_DRAFT", "WARNING", "AI analysis draft needs clinical review",
                    draft.getPeriodLabel(), "ai-analysis", draft.getId(), null));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issues", issues);
        out.put("pendingReview", issues.stream().filter(x -> Arrays.asList("REVIEW", "AI_DRAFT").contains(x.get("type"))).count());
        out.put("lowConfidence", issues.stream().filter(x -> "LOW_CONFIDENCE".equals(x.get("type"))).count());
        out.put("total", issues.size());
        return out;
    }

    public Map<String, Object> medicationSafety(Long patientId) {
        scope.requirePatient(patientId);
        List<Medication> active = medications.selectList(new QueryWrapper<Medication>()
                .eq("patient_id", patientId).eq("is_active", 1).orderByAsc("drug_name"));
        PatientClinical profile = clinical.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        String allergies = profile == null || profile.getAllergyDrugs() == null ? "" : profile.getAllergyDrugs().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, List<Medication>> byGeneric = active.stream().collect(Collectors.groupingBy(this::normalizedMedicationName));
        for (Map.Entry<String, List<Medication>> entry : byGeneric.entrySet()) {
            if (!entry.getKey().isEmpty() && entry.getValue().size() > 1) {
                findings.add(safetyFinding("DUPLICATE", "WARNING", "Possible duplicate therapy",
                        entry.getValue().stream().map(Medication::getDrugName).collect(Collectors.joining(" + ")),
                        "Confirm whether both active entries are intended."));
            }
        }
        for (Medication medication : active) {
            String name = normalizedMedicationName(medication);
            if (!name.isEmpty() && allergies.contains(name)) {
                findings.add(safetyFinding("ALLERGY", "CRITICAL", "Medication matches the recorded allergy list",
                        medication.getDrugName(), "Do not change treatment in the app; contact the prescribing clinician or pharmacist promptly."));
            }
            String haystack = (medication.getDrugName() + " " + medication.getGenericName() + " " + medication.getCategory()).toLowerCase(Locale.ROOT);
            if (haystack.contains("nsaid") || haystack.contains("ibuprofen") || haystack.contains("naproxen")) {
                findings.add(safetyFinding("RENAL", "WARNING", "Renal-dose/renal-safety review suggested",
                        medication.getDrugName(), "NSAIDs may be unsuitable in advanced kidney disease; verify the prescriber plan."));
            }
        }
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT * FROM medication_safety_rule WHERE enabled=1 ORDER BY id");
        Set<String> names = active.stream().flatMap(m -> Arrays.stream((m.getDrugName() + " " + m.getGenericName()).toLowerCase(Locale.ROOT).split("[,/+]")))
                .map(String::trim).collect(Collectors.toSet());
        for (Map<String, Object> rule : rules) {
            String a = String.valueOf(rule.get("ingredient_a")).toLowerCase(Locale.ROOT);
            String b = String.valueOf(rule.get("ingredient_b")).toLowerCase(Locale.ROOT);
            if (containsMedication(names, a) && containsMedication(names, b)) {
                findings.add(safetyFinding("INTERACTION", String.valueOf(rule.get("severity")),
                        "Potential medication interaction", a + " + " + b,
                        String.valueOf(rule.get("message")) + (rule.get("renal_note") == null ? "" : " " + rule.get("renal_note"))));
            }
        }
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<MedicationIntake> recent = intakes.selectList(new QueryWrapper<MedicationIntake>()
                .eq("patient_id", patientId).ge("scheduled_at", since));
        long taken = recent.stream().filter(x -> "TAKEN".equals(x.getStatus())).count();
        long missed = recent.stream().filter(x -> Arrays.asList("MISSED", "SKIPPED").contains(x.getStatus())).count();
        BigDecimal adherence = taken + missed == 0 ? null : BigDecimal.valueOf(taken * 100.0 / (taken + missed)).setScale(1, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("medications", active);
        out.put("allergies", profile == null ? null : profile.getAllergyDrugs());
        out.put("findings", findings);
        out.put("adherenceRate", adherence);
        out.put("taken", taken);
        out.put("missedOrSkipped", missed);
        out.put("disclaimer", "Screening is decision support only. A clinician or pharmacist must confirm every medication change.");
        return out;
    }

    public Map<String, Object> dialysisQuality(Long patientId, int days) {
        scope.requirePatient(patientId);
        int range = Math.max(7, Math.min(days, 365));
        PatientClinical profile = clinical.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        BigDecimal dryWeight = profile == null ? null : profile.getTargetDryWeight();
        List<DialysisRecord> rows = dialysisRecords.selectList(new QueryWrapper<DialysisRecord>()
                .eq("patient_id", patientId).ge("record_date", LocalDate.now().minusDays(range)).orderByAsc("record_date"));
        List<Map<String, Object>> sessions = new ArrayList<>();
        List<BigDecimal> idwgValues = new ArrayList<>(), ufrValues = new ArrayList<>(), ktvValues = new ArrayList<>(), urrValues = new ArrayList<>();
        int accessIssues = 0;
        for (DialysisRecord row : rows) {
            BigDecimal idwgPercent = dryWeight == null || dryWeight.signum() == 0 || row.getWeightGain() == null ? null
                    : row.getWeightGain().multiply(BigDecimal.valueOf(100)).divide(dryWeight, 2, RoundingMode.HALF_UP);
            BigDecimal ufr = dryWeight == null || dryWeight.signum() == 0 || row.getUfAmount() == null || row.getSessionMinutes() == null || row.getSessionMinutes() <= 0 ? null
                    : row.getUfAmount().multiply(BigDecimal.valueOf(60000)).divide(dryWeight.multiply(BigDecimal.valueOf(row.getSessionMinutes())), 2, RoundingMode.HALF_UP);
            if (idwgPercent != null) idwgValues.add(idwgPercent);
            if (ufr != null) ufrValues.add(ufr);
            if (row.getKtv() != null) ktvValues.add(row.getKtv());
            if (row.getUrr() != null) urrValues.add(row.getUrr());
            if (row.getAccessIssue() != null && !row.getAccessIssue().trim().isEmpty()) accessIssues++;
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", row.getId()); session.put("date", row.getRecordDate()); session.put("idwgPercent", idwgPercent);
            session.put("ufr", ufr); session.put("ktv", row.getKtv()); session.put("urr", row.getUrr());
            session.put("accessIssue", row.getAccessIssue());
            sessions.add(session);
        }
        List<Map<String, Object>> flags = new ArrayList<>();
        BigDecimal avgIdwg = average(idwgValues), avgUfr = average(ufrValues), avgKtv = average(ktvValues), avgUrr = average(urrValues);
        if (avgIdwg != null && avgIdwg.compareTo(new BigDecimal("4.0")) > 0) flags.add(metricFlag("IDWG", "WARNING", "Average interdialytic weight gain is above 4% of target dry weight."));
        if (avgUfr != null && avgUfr.compareTo(new BigDecimal("13")) > 0) flags.add(metricFlag("UFR", "CRITICAL", "Average ultrafiltration rate is above 13 mL/kg/hour."));
        if (avgKtv != null && avgKtv.compareTo(new BigDecimal("1.2")) < 0) flags.add(metricFlag("KT/V", "WARNING", "Average recorded Kt/V is below 1.2."));
        if (avgUrr != null && avgUrr.compareTo(new BigDecimal("65")) < 0) flags.add(metricFlag("URR", "WARNING", "Average recorded URR is below 65%."));
        if (accessIssues > 0) flags.add(metricFlag("ACCESS", "WARNING", accessIssues + " sessions include a vascular-access concern."));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rangeDays", range); out.put("sessions", sessions); out.put("sessionCount", rows.size());
        out.put("avgIdwgPercent", avgIdwg); out.put("avgUfr", avgUfr); out.put("avgKtv", avgKtv); out.put("avgUrr", avgUrr);
        out.put("accessIssueCount", accessIssues); out.put("flags", flags);
        out.put("scheduleWeekdays", profile == null ? null : profile.getDialysisWeekdays());
        out.put("scheduleTime", profile == null ? null : profile.getDialysisTime());
        out.put("disclaimer", "Thresholds are screening cues, not treatment targets. The dialysis team must interpret them in clinical context.");
        return out;
    }

    public Map<String, Object> emergencyCard(Long patientId) {
        Patient patient = scope.requirePatient(patientId);
        PatientClinical profile = clinical.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        PatientHealthTarget target = targets.selectOne(new QueryWrapper<PatientHealthTarget>().eq("patient_id", patientId).last("limit 1"));
        DialysisRecord latest = dialysisRecords.selectOne(new QueryWrapper<DialysisRecord>().eq("patient_id", patientId).orderByDesc("record_date").last("limit 1"));
        List<Medication> active = medications.selectList(new QueryWrapper<Medication>().eq("patient_id", patientId).eq("is_active", 1).orderByAsc("drug_name"));
        List<AlertRecord> unresolved = alerts.selectList(new QueryWrapper<AlertRecord>().eq("patient_id", patientId)
                .and(q -> q.isNull("status").or().ne("status", "RESOLVED")).orderByDesc("triggered_at").last("limit 10"));
        Map<String, Object> safePatient = new LinkedHashMap<>();
        safePatient.put("id", patient.getId()); safePatient.put("name", patient.getName()); safePatient.put("gender", patient.getGender());
        safePatient.put("birthDate", patient.getBirthDate()); safePatient.put("phone", patient.getPhone());
        safePatient.put("emergencyContact", patient.getEmergencyContact()); safePatient.put("emergencyPhone", patient.getEmergencyPhone());
        safePatient.put("medicalHistory", patient.getMedicalHistory());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", safePatient); out.put("clinical", profile); out.put("target", target); out.put("medications", active);
        out.put("latestDialysis", latest); out.put("unresolvedAlerts", unresolved); out.put("generatedAt", LocalDateTime.now());
        out.put("disclaimer", "For emergency communication only. Verify identity, medication list, and allergies with the patient or treating team.");
        return out;
    }

    private Map<String, Object> attentionItem(String type, String severity, String title, Object evidence,
                                               LocalDateTime dueAt, String action, String path, Object sourceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("severity", severity == null ? "INFO" : severity); row.put("title", title);
        row.put("evidence", evidence); row.put("dueAt", dueAt); row.put("recommendedAction", action);
        row.put("path", path); row.put("sourceId", sourceId);
        row.put("slaMinutes", "CRITICAL".equals(severity) ? 15 : "WARNING".equals(severity) ? 240 : 1440);
        return row;
    }

    private int severityRank(Map<String, Object> row) {
        return "CRITICAL".equals(row.get("severity")) ? 0 : "WARNING".equals(row.get("severity")) ? 1 : 2;
    }

    private Map<String, Object> issue(String type, String severity, String title, String detail,
                                      String sourceType, Long sourceId, BigDecimal confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("severity", severity); row.put("title", title); row.put("detail", detail);
        row.put("sourceType", sourceType); row.put("sourceId", sourceId); row.put("confidence", confidence);
        return row;
    }

    private String recordLabel(MedicalRecord record) {
        return (record.getRecordDate() == null ? "Date missing" : record.getRecordDate()) + " · "
                + (record.getHospitalName() == null ? "Facility missing" : record.getHospitalName());
    }

    private String normalizedMedicationName(Medication medication) {
        String value = medication.getGenericName() == null || medication.getGenericName().trim().isEmpty()
                ? medication.getDrugName() : medication.getGenericName();
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsMedication(Set<String> names, String needle) {
        return names.stream().anyMatch(name -> name.contains(needle) || needle.contains(name));
    }

    private Map<String, Object> safetyFinding(String type, String severity, String title, String evidence, String action) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("severity", severity); row.put("title", title); row.put("evidence", evidence); row.put("action", action);
        return row;
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> metricFlag(String metric, String severity, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", metric); row.put("severity", severity); row.put("message", message);
        return row;
    }
}
