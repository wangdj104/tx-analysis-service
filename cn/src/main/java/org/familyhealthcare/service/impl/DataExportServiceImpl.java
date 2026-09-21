package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DataExportRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports patient data in CSV format.
 */
@Service
public class DataExportServiceImpl implements DataExportService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Autowired
    private BpPatternAnalysisService bpPatternAnalysisService;

    @Autowired
    private NutritionDiaryService nutritionDiaryService;

    @Autowired
    private ComplicationRecordService complicationRecordService;

    @Autowired
    private MedicationService medicationService;

    @Autowired
    private BpSelfMonitorRecordService bpSelfMonitorRecordService;

    @Override
    public byte[] exportCsv(DataExportRequestVO request) throws Exception {
        Long patientId = request.getPatientId();
        if (patientId == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(patientId);

        String dataType = request.getDataType() != null ? request.getDataType() : "dialysis";
        String csv;
        org.familyhealthcare.util.HealthDateRange range = org.familyhealthcare.util.HealthDateRange.of(request.getTimeType(), request.getTimeValue());

        switch (dataType) {
            case "dialysis":
                csv = exportDialysisData(patientId, request.getTimeType(), request.getTimeValue());
                break;
            case "bp_analysis":
                csv = exportBpAnalysisData(patientId, range);
                break;
            case "nutrition":
                csv = exportNutritionData(patientId, range);
                break;
            case "complication":
                csv = exportComplicationData(patientId, range);
                break;
            case "medication":
                csv = exportMedicationData(patientId, range);
                break;
            case "bp_self_monitor":
                csv = exportBpSelfMonitorData(patientId, range);
                break;
            default:
                throw new IllegalStateException("Unsupported data type: " + dataType);
        }

        if (Boolean.TRUE.equals(request.getMasked())) csv = deidentifyCsv(csv);

        // Include a UTF-8 BOM so spreadsheet applications detect the encoding correctly.
        byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + csvBytes.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(csvBytes, 0, result, bom.length, csvBytes.length);
        return result;
    }

    private String exportDialysisData(Long patientId, String timeType, String timeValue) {
        List<DialysisRecord> records = dialysisRecordService.listByFilter(timeType, timeValue, patientId);
        StringBuilder sb = new StringBuilder();
        sb.append("Date,Pre-dialysis weight (kg),Post-dialysis weight (kg),Interdialytic weight gain (kg),Ultrafiltration volume (kg),Systolic pressure,Diastolic pressure,Ultrafiltration status,Average daily weight gain (kg)\n");
        for (DialysisRecord r : records) {
            sb.append(nvl(r.getRecordDate())).append(",");
            sb.append(nvl(r.getOnWeight())).append(",");
            sb.append(nvl(r.getOffWeight())).append(",");
            sb.append(nvl(r.getWeightGain())).append(",");
            sb.append(nvl(r.getUfAmount())).append(",");
            sb.append(nvl(r.getSystolicBp())).append(",");
            sb.append(nvl(r.getDiastolicBp())).append(",");
            sb.append(nvl(r.getDehydrationStatus())).append(",");
            sb.append(nvl(r.getDailyWeightGain())).append("\n");
        }
        return sb.toString();
    }

    private String exportBpAnalysisData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<BpPatternAnalysis> list = bpPatternAnalysisService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append("Analysis date,Time dimension,Time value,Average systolic pressure,Average diastolic pressure,Maximum systolic pressure,Minimum systolic pressure,Standard deviation,Orthostatic hypotension count,Low blood pressure count,High blood pressure count,Analysis summary\n");
        for (BpPatternAnalysis r : list) {
            if (!range.contains(r.getAnalysisDate())) continue;
            sb.append(nvl(r.getAnalysisDate())).append(",");
            sb.append(nvl(r.getTimeType())).append(",");
            sb.append(nvl(r.getTimeValue())).append(",");
            sb.append(nvl(r.getAvgSystolic())).append(",");
            sb.append(nvl(r.getAvgDiastolic())).append(",");
            sb.append(nvl(r.getMaxSystolic())).append(",");
            sb.append(nvl(r.getMinSystolic())).append(",");
            sb.append(nvl(r.getStdDeviation())).append(",");
            sb.append(nvl(r.getOrthostaticCount())).append(",");
            sb.append(nvl(r.getLowBpCount())).append(",");
            sb.append(nvl(r.getHighBpCount())).append(",");
            sb.append("\"").append(r.getAnalysisSummary() != null ? r.getAnalysisSummary().replace("\"", "'") : "").append("\"\n");
        }
        return sb.toString();
    }

    private String exportNutritionData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<NutritionDiary> list = nutritionDiaryService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append("Date,Weight (kg),Appetite,Breakfast,Lunch,Dinner,Snack,Fluid intake (ml),Symptoms,Notes\n");
        for (NutritionDiary r : list) {
            if (!range.contains(r.getRecordDate())) continue;
            sb.append(nvl(r.getRecordDate())).append(",");
            sb.append(nvl(r.getBodyWeight())).append(",");
            sb.append(nvl(r.getAppetite())).append(",");
            sb.append(yn(r.getMealBreakfast())).append(",");
            sb.append(yn(r.getMealLunch())).append(",");
            sb.append(yn(r.getMealDinner())).append(",");
            sb.append(yn(r.getMealSnack())).append(",");
            sb.append(nvl(r.getFluidIntake())).append(",");
            sb.append("\"").append(r.getSymptoms() != null ? r.getSymptoms().replace("\"", "'") : "").append("\"").append(",");
            sb.append("\"").append(r.getRemark() != null ? r.getRemark().replace("\"", "'") : "").append("\"\n");
        }
        return sb.toString();
    }

    private String exportComplicationData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<ComplicationRecord> list = complicationRecordService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append("Occurrence date,Complication type,Severity,Description,Treatment,Outcome\n");
        for (ComplicationRecord r : list) {
            if (!range.contains(r.getOccurrenceDate())) continue;
            sb.append(nvl(r.getOccurrenceDate())).append(",");
            sb.append(nvl(r.getComplicationType())).append(",");
            sb.append(nvl(r.getSeverity())).append(",");
            sb.append("\"").append(r.getDescription() != null ? r.getDescription().replace("\"", "'") : "").append("\"").append(",");
            sb.append("\"").append(r.getTreatmentMeasures() != null ? r.getTreatmentMeasures().replace("\"", "'") : "").append("\"").append(",");
            sb.append("\"").append(r.getOutcome() != null ? r.getOutcome().replace("\"", "'") : "").append("\"\n");
        }
        return sb.toString();
    }

    private String exportMedicationData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        StringBuilder sb = new StringBuilder("Source,Medication,Scheduled time,Actual intake time,Dose,Status,Notes\n");
        for (MedicationLog r : medicationService.listLogs(patientId, null)) {
            if (r.getAdministrationTime() == null || !range.contains(r.getAdministrationTime().toLocalDate())) continue;
            sb.append(csvRow("Manual record", r.getMedication() == null ? "Deleted medication" : r.getMedication().getDrugName(),
                    "", r.getAdministrationTime(), r.getDosage(), "Taken", r.getRemark()));
        }
        for (MedicationIntake r : intakeMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MedicationIntake>().eq("patient_id", patientId).orderByAsc("scheduled_at"))) {
            if (r.getScheduledAt() == null || !range.contains(r.getScheduledAt().toLocalDate())) continue;
            Medication medication = medicationService.getById(r.getMedicationId());
            sb.append(csvRow("Reminder check-in", medication == null ? "Deleted medication" : medication.getDrugName(), r.getScheduledAt(),
                    "TAKEN".equals(r.getStatus()) ? r.getActionAt() : "", r.getDosage(), r.getStatus(), r.getReason()));
        }
        return sb.toString();
    }

    @Autowired private org.familyhealthcare.mapper.MedicationIntakeMapper intakeMapper;

    private String csvRow(Object... cells) {
        return java.util.Arrays.stream(cells).map(v -> "\"" + nvl(v).replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "\n";
    }

    private String exportBpSelfMonitorData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<BpSelfMonitorRecord> list = bpSelfMonitorRecordService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append("Record date,Record time,Measurement type,Systolic pressure (mmHg),Diastolic pressure (mmHg),Blood glucose,Blood glucose unit,Measurement period,Notes\n");
        for (BpSelfMonitorRecord r : list) {
            if (!range.contains(r.getRecordDate())) continue;
            sb.append(nvl(r.getRecordDate())).append(",");
            sb.append(nvl(r.getRecordTime())).append(",");
            sb.append(nvl(r.getMeasureType())).append(",");
            sb.append(nvl(r.getSystolicBp())).append(",");
            sb.append(nvl(r.getDiastolicBp())).append(",");
            sb.append(nvl(r.getBloodGlucose())).append(",");
            sb.append(nvl(r.getBgUnit())).append(",");
            sb.append(nvl(r.getMeasurePeriod())).append(",");
            sb.append("\"").append(r.getRemark() != null ? r.getRemark().replace("\"", "'") : "").append("\"\n");
        }
        return sb.toString();
    }

    private String nvl(Object v) { return v != null ? v.toString() : ""; }
    private String yn(Boolean v) { return Boolean.TRUE.equals(v) ? "Yes" : "No"; }

    private String deidentifyCsv(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)", "$1****$2")
                .replaceAll("(?<![0-9A-Za-z])(\\d{3})\\d{11}([0-9Xx]{4})(?![0-9A-Za-z])", "$1***********$2");
    }
}
