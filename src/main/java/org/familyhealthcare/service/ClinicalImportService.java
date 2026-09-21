package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ClinicalImportService {
    @Autowired private DataScopeHelper scope;
    @Autowired private BpSelfMonitorRecordService vitalService;
    @Autowired private BpSelfMonitorRecordMapper vitalMapper;
    @Autowired private MedicalRecordService medicalRecordService;
    @Autowired private MedicalRecordMapper medicalRecordMapper;
    @Autowired private JdbcTemplate jdbc;

    public Map<String, Object> preview(Long patientId, Map<String, Object> payload) {
        scope.requirePatient(patientId);
        List<Map<String, Object>> parsed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Object bundleObject = payload.get("bundle");
        if (bundleObject instanceof Map) parseFhirBundle(castMap(bundleObject), parsed, warnings);
        Object readingsObject = payload.get("readings");
        if (readingsObject instanceof List) parseDeviceReadings(castList(readingsObject), parsed, warnings);
        if (parsed.isEmpty() && warnings.isEmpty()) warnings.add("No supported observations were found. Provide a FHIR Bundle or device readings.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", parsed); out.put("warnings", warnings); out.put("itemCount", parsed.size());
        out.put("requiresConfirmation", true);
        out.put("supported", Arrays.asList("FHIR Observation", "blood pressure", "blood glucose", "laboratory value", "device reading JSON"));
        return out;
    }

    @Transactional
    public Map<String, Object> commit(Long patientId, Map<String, Object> payload) {
        Patient patient = scope.requirePatient(patientId);
        Map<String, Object> preview = preview(patientId, payload);
        List<Map<String, Object>> items = castListOfMaps(preview.get("items"));
        int created = 0, skipped = 0, errors = 0;
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> item : items) {
            try {
                String kind = String.valueOf(item.get("kind"));
                String externalId = string(item.get("externalId"));
                if ("VITAL".equals(kind)) {
                    if (existsVital(patientId, externalId)) { skipped++; continue; }
                    BpSelfMonitorRecord row = new BpSelfMonitorRecord();
                    row.setPatientId(patientId); row.setRecordDate(LocalDate.parse(String.valueOf(item.get("date"))));
                    row.setRecordTime(string(item.get("time"))); row.setMeasureType(String.valueOf(item.get("measureType")));
                    row.setSystolicBp(integer(item.get("systolic"))); row.setDiastolicBp(integer(item.get("diastolic")));
                    row.setBloodGlucose(decimal(item.get("glucose"))); row.setBgUnit(string(item.get("unit")));
                    row.setSourceType(String.valueOf(item.get("sourceType"))); row.setSourceExternalId(externalId);
                    row.setVerificationStatus("REVIEW_REQUIRED"); row.setRemark("Imported data; verify against the source device or report.");
                    vitalService.saveOwned(row); created++;
                } else if ("LAB".equals(kind)) {
                    if (existsMedicalRecord(patientId, String.valueOf(item.get("sourceType")), externalId)) { skipped++; continue; }
                    MedicalRecord record = new MedicalRecord();
                    record.setPatientId(patientId); record.setPatientName(patient.getName());
                    record.setRecordDate(LocalDate.parse(String.valueOf(item.get("date")))); record.setRecordType("OTHER");
                    record.setHospitalName(string(item.get("organization")) == null ? "Imported clinical data" : string(item.get("organization")));
                    record.setSourceType(String.valueOf(item.get("sourceType"))); record.setSourceExternalId(externalId);
                    record.setVerificationStatus("REVIEW_REQUIRED"); record.setRemark("Imported data; verify before clinical use.");
                    MedicalRecordItem detail = new MedicalRecordItem();
                    detail.setItemCode(string(item.get("code"))); detail.setItemName(String.valueOf(item.get("display")));
                    detail.setResultValue(String.valueOf(item.get("value"))); detail.setUnit(string(item.get("unit")));
                    detail.setReferenceRange(string(item.get("referenceRange"))); detail.setAiConfidence(decimal(item.get("confidence")));
                    medicalRecordService.saveRecordWithItems(record, Collections.singletonList(detail)); created++;
                }
            } catch (Exception e) {
                errors++;
                messages.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        String source = payload.get("bundle") instanceof Map ? "FHIR" : "DEVICE";
        jdbc.update("INSERT INTO clinical_import_batch(user_id,patient_id,source_type,status,item_count,error_count,summary) VALUES(?,?,?,?,?,?,?)",
                scope.requireUserId(), patientId, source, errors == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS", created, errors,
                "Created " + created + ", skipped duplicates " + skipped + (messages.isEmpty() ? "" : ", first error: " + messages.get(0)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created); out.put("skipped", skipped); out.put("errors", errors); out.put("messages", messages);
        out.put("requiresConfirmation", false); out.put("verificationStatus", "REVIEW_REQUIRED");
        return out;
    }

    private void parseFhirBundle(Map<String, Object> bundle, List<Map<String, Object>> out, List<String> warnings) {
        Object resourceType = bundle.get("resourceType");
        if (!"Bundle".equals(resourceType)) { warnings.add("The FHIR payload is not a Bundle."); return; }
        for (Object entryObject : list(bundle.get("entry"))) {
            Map<String, Object> entry = castMap(entryObject);
            Map<String, Object> resource = castMap(entry.get("resource"));
            if (!"Observation".equals(resource.get("resourceType"))) continue;
            parseObservation(resource, out, warnings);
        }
    }

    private void parseObservation(Map<String, Object> resource, List<Map<String, Object>> out, List<String> warnings) {
        String id = string(resource.get("id"));
        String externalId = id == null ? "observation-" + UUID.randomUUID() : id;
        Map<String, Object> code = castMap(resource.get("code"));
        String observationCode = codingValue(code, "code");
        String display = Optional.ofNullable(codingValue(code, "display")).orElse(string(code.get("text")));
        DateParts date = parseDate(firstNonNull(resource.get("effectiveDateTime"), resource.get("issued"), resource.get("effectiveInstant")), warnings);
        Map<String, Object> components = new HashMap<>();
        for (Object componentObject : list(resource.get("component"))) {
            Map<String, Object> component = castMap(componentObject);
            String componentCode = codingValue(castMap(component.get("code")), "code");
            components.put(componentCode, quantityValue(component));
        }
        boolean bloodPressure = "85354-9".equals(observationCode) || components.containsKey("8480-6") || components.containsKey("8462-4");
        if (bloodPressure) {
            Map<String, Object> sys = castMap(components.get("8480-6")), dia = castMap(components.get("8462-4"));
            if (sys.isEmpty() || dia.isEmpty()) { warnings.add("Blood-pressure observation " + externalId + " is missing systolic or diastolic data."); return; }
            Map<String, Object> row = baseImport("VITAL", "FHIR", externalId, date);
            row.put("measureType", "BP"); row.put("systolic", integer(sys.get("value"))); row.put("diastolic", integer(dia.get("value"))); row.put("unit", "mmHg");
            out.add(row); return;
        }
        Map<String, Object> quantity = quantityValue(resource);
        if (quantity.isEmpty() && resource.get("valueString") == null) { warnings.add("Observation " + externalId + " has no supported scalar value."); return; }
        String normalizedDisplay = display == null ? observationCode : display;
        boolean glucose = (observationCode != null && Arrays.asList("2339-0", "2345-7", "15074-8").contains(observationCode))
                || normalizedDisplay != null && normalizedDisplay.toLowerCase(Locale.ROOT).contains("glucose");
        if (glucose) {
            Map<String, Object> row = baseImport("VITAL", "FHIR", externalId, date);
            row.put("measureType", "BG"); row.put("glucose", quantity.get("value")); row.put("unit", Optional.ofNullable(string(quantity.get("unit"))).orElse("mmol/L"));
            out.add(row); return;
        }
        Map<String, Object> row = baseImport("LAB", "FHIR", externalId, date);
        row.put("code", observationCode); row.put("display", normalizedDisplay == null ? "Imported observation" : normalizedDisplay);
        row.put("value", quantity.isEmpty() ? resource.get("valueString") : quantity.get("value")); row.put("unit", quantity.get("unit"));
        row.put("referenceRange", referenceRange(resource)); row.put("confidence", BigDecimal.ONE);
        out.add(row);
    }

    private void parseDeviceReadings(List<Object> readings, List<Map<String, Object>> out, List<String> warnings) {
        int index = 0;
        for (Object value : readings) {
            index++;
            Map<String, Object> reading = castMap(value);
            DateParts date = parseDate(firstNonNull(reading.get("observedAt"), reading.get("date")), warnings);
            Map<String, Object> row = baseImport("VITAL", "DEVICE",
                    Optional.ofNullable(string(reading.get("sourceExternalId"))).orElse("device-" + index + "-" + date.date + "T" + date.time), date);
            Integer systolic = integer(reading.get("systolic")), diastolic = integer(reading.get("diastolic"));
            BigDecimal glucose = decimal(reading.get("glucose"));
            if (systolic != null || diastolic != null) {
                if (systolic == null || diastolic == null) { warnings.add("Device reading " + index + " needs both systolic and diastolic values."); continue; }
                row.put("measureType", glucose == null ? "BP" : "BOTH"); row.put("systolic", systolic); row.put("diastolic", diastolic);
            } else if (glucose != null) row.put("measureType", "BG");
            else { warnings.add("Device reading " + index + " has no supported measurement."); continue; }
            row.put("glucose", glucose); row.put("unit", Optional.ofNullable(string(reading.get("unit"))).orElse("mmol/L")); out.add(row);
        }
    }

    private boolean existsVital(Long patientId, String externalId) {
        return externalId != null && vitalMapper.selectCount(new QueryWrapper<BpSelfMonitorRecord>()
                .eq("patient_id", patientId).in("source_type", "FHIR", "DEVICE").eq("source_external_id", externalId)) > 0;
    }

    private boolean existsMedicalRecord(Long patientId, String source, String externalId) {
        return externalId != null && medicalRecordMapper.selectCount(new QueryWrapper<MedicalRecord>()
                .eq("patient_id", patientId).eq("source_type", source).eq("source_external_id", externalId)) > 0;
    }

    private Map<String, Object> baseImport(String kind, String source, String id, DateParts date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind); row.put("sourceType", source); row.put("externalId", id); row.put("date", date.date); row.put("time", date.time);
        return row;
    }

    private DateParts parseDate(Object value, List<String> warnings) {
        if (value == null) return new DateParts(LocalDate.now(), LocalTime.now().withSecond(0).withNano(0).toString());
        String text = String.valueOf(value);
        try { OffsetDateTime dt = OffsetDateTime.parse(text); return new DateParts(dt.toLocalDate(), hhmm(dt.toLocalTime())); }
        catch (DateTimeParseException ignored) { }
        try { LocalDateTime dt = LocalDateTime.parse(text); return new DateParts(dt.toLocalDate(), hhmm(dt.toLocalTime())); }
        catch (DateTimeParseException ignored) { }
        try { return new DateParts(LocalDate.parse(text.substring(0, Math.min(10, text.length()))), null); }
        catch (RuntimeException e) { warnings.add("Could not parse observation date '" + text + "'; today's date was used."); return new DateParts(LocalDate.now(), null); }
    }

    private String hhmm(LocalTime time) { return String.format("%02d:%02d", time.getHour(), time.getMinute()); }
    private Object firstNonNull(Object... values) { for (Object value : values) if (value != null) return value; return null; }
    private Map<String, Object> quantityValue(Map<String, Object> source) { return castMap(source.get("valueQuantity")); }
    private String codingValue(Map<String, Object> code, String key) { List<Object> coding = list(code.get("coding")); return coding.isEmpty() ? null : string(castMap(coding.get(0)).get(key)); }
    private String referenceRange(Map<String, Object> resource) {
        List<Object> ranges = list(resource.get("referenceRange")); if (ranges.isEmpty()) return null;
        Map<String, Object> range = castMap(ranges.get(0)), low = castMap(range.get("low")), high = castMap(range.get("high"));
        if (low.isEmpty() && high.isEmpty()) return string(range.get("text"));
        return string(low.get("value")) + "-" + string(high.get("value")) + " " + Optional.ofNullable(string(high.get("unit"))).orElse("");
    }
    private String string(Object value) { if (value == null) return null; String text = String.valueOf(value).trim(); return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text; }
    private Integer integer(Object value) { BigDecimal decimal = decimal(value); return decimal == null ? null : decimal.intValue(); }
    private BigDecimal decimal(Object value) { try { return value == null ? null : new BigDecimal(String.valueOf(value)); } catch (NumberFormatException e) { return null; } }
    @SuppressWarnings("unchecked") private Map<String, Object> castMap(Object value) { return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap(); }
    @SuppressWarnings("unchecked") private List<Object> castList(Object value) { return value instanceof List ? (List<Object>) value : Collections.emptyList(); }
    private List<Object> list(Object value) { return castList(value); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> castListOfMaps(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : Collections.emptyList(); }

    private static class DateParts {
        final LocalDate date; final String time;
        DateParts(LocalDate date, String time) { this.date = date; this.time = time; }
    }
}
