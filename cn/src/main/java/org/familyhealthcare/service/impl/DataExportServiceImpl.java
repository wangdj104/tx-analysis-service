package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DataExportRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static org.familyhealthcare.util.ExportLocalization.text;
import static org.familyhealthcare.util.ExportLocalization.label;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports patient data in CSV format.
 */
@Service
public class DataExportServiceImpl implements DataExportService {
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard medicationTracking;

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
        if (patientId == null) throw new IllegalStateException(text("Select a patient", "请选择患者"));
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
                throw new IllegalStateException(text("Unsupported data type: ", "不支持的数据类型：") + dataType);
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
        sb.append(text("Date,Pre-dialysis weight (kg),Post-dialysis weight (kg),Interdialytic weight gain (kg),Ultrafiltration volume (kg),Systolic pressure,Diastolic pressure,Ultrafiltration status,Average daily weight gain (kg)\n", "日期,透前体重（kg）,透后体重（kg）,透析间期增重（kg）,超滤量（kg）,收缩压,舒张压,超滤状态,日均增重（kg）\n"));
        for (DialysisRecord r : records) {
            sb.append(csvRow(r.getRecordDate(), r.getOnWeight(), r.getOffWeight(), r.getWeightGain(),
                    r.getUfAmount(), r.getSystolicBp(), r.getDiastolicBp(), label(r.getDehydrationStatus()), r.getDailyWeightGain()));
        }
        return sb.toString();
    }

    private String exportBpAnalysisData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<BpPatternAnalysis> list = bpPatternAnalysisService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append(text("Analysis date,Time dimension,Time value,Average systolic pressure,Average diastolic pressure,Maximum systolic pressure,Minimum systolic pressure,Standard deviation,Orthostatic hypotension count,Low blood pressure count,High blood pressure count,Analysis summary\n", "分析日期,时间维度,周期,平均收缩压,平均舒张压,最高收缩压,最低收缩压,标准差,体位性低血压次数,低血压次数,高血压次数,分析摘要\n"));
        for (BpPatternAnalysis r : list) {
            if (!range.contains(r.getAnalysisDate())) continue;
            sb.append(csvRow(r.getAnalysisDate(), label(r.getTimeType()), r.getTimeValue(), r.getAvgSystolic(),
                    r.getAvgDiastolic(), r.getMaxSystolic(), r.getMinSystolic(), r.getStdDeviation(), r.getOrthostaticCount(),
                    r.getLowBpCount(), r.getHighBpCount(), r.getAnalysisSummary()));
        }
        return sb.toString();
    }

    private String exportNutritionData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<NutritionDiary> list = nutritionDiaryService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append(text("Date,Weight (kg),Appetite,Breakfast,Lunch,Dinner,Snack,Fluid intake (ml),Symptoms,Notes\n", "日期,体重（kg）,食欲,早餐,午餐,晚餐,加餐,液体摄入（ml）,症状,备注\n"));
        for (NutritionDiary r : list) {
            if (!range.contains(r.getRecordDate())) continue;
            sb.append(csvRow(r.getRecordDate(), r.getBodyWeight(), label(r.getAppetite()), yn(r.getMealBreakfast()),
                    yn(r.getMealLunch()), yn(r.getMealDinner()), yn(r.getMealSnack()), r.getFluidIntake(), r.getSymptoms(), r.getRemark()));
        }
        return sb.toString();
    }

    private String exportComplicationData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<ComplicationRecord> list = complicationRecordService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append(text("Occurrence date,Complication type,Severity,Description,Treatment,Outcome\n", "发生日期,并发症类型,严重程度,描述,处理措施,结局\n"));
        for (ComplicationRecord r : list) {
            if (!range.contains(r.getOccurrenceDate())) continue;
            sb.append(csvRow(r.getOccurrenceDate(), label(r.getComplicationType()), label(r.getSeverity()),
                    r.getDescription(), r.getTreatmentMeasures(), r.getOutcome()));
        }
        return sb.toString();
    }

    private String exportMedicationData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy=medicationTracking.legacyRead(patientId);
        long actor=dataScopeHelper.requireUserId();
        StringBuilder sb = new StringBuilder(text("Source,Medication,Scheduled time,Actual intake time,Dose,Status,Notes\n", "来源,药品,计划时间,实际服药时间,剂量,状态,备注\n"));
        for (MedicationLog r : legacy.logs(actor,medicationService.listLogs(patientId, null))) {
            if (r.getAdministrationTime() == null || !range.contains(r.getAdministrationTime().toLocalDate())) continue;
            sb.append(csvRow(text("Manual record", "手工记录"), r.getMedication() == null ? text("Deleted medication", "已删除药品") : r.getMedication().getDrugName(),
                    "", r.getAdministrationTime(), r.getDosage(), text("Taken", "已服药"), r.getRemark()));
        }
        for (MedicationIntake r : legacy.intakes(actor,intakeMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MedicationIntake>().eq("patient_id", patientId).orderByAsc("scheduled_at")))) {
            if (r.getScheduledAt() == null || !range.contains(r.getScheduledAt().toLocalDate())) continue;
            Medication medication = medicationService.getById(r.getMedicationId());
            sb.append(csvRow(text("Reminder check-in", "提醒打卡"), medication == null ? text("Deleted medication", "已删除药品") : medication.getDrugName(), r.getScheduledAt(),
                    r.getNextAction()!=null?r.getActualAtUtc():"TAKEN".equals(r.getStatus()) ? r.getActionAt() : "", r.getNextAction()!=null?null:r.getDosage(),
                    r.getNextAction()!=null?String.valueOf(r.getFollowUpState())+" / "+String.valueOf(r.getFollowUpKind()):label(r.getStatus()),r.getNextAction()!=null?text("Review actual facts in the medication workflow; unknown time or amount remains unknown.","请在用药闭环核对实际事实；未知时间或数量保持未知。"):r.getReason()));
        }
        legacy.finish(actor);return sb.toString();
    }

    @Autowired private org.familyhealthcare.mapper.MedicationIntakeMapper intakeMapper;

    private String csvRow(Object... cells) {
        return java.util.Arrays.stream(cells).map(v -> {
            // CSV 引号只保护逗号、引号和换行，不能阻止公式；沿用护理报告的文本保护，真实数值仍保持数值。
            if (v instanceof Number) return v.toString();
            String value = nvl(v);
            if (dangerousCsvText(value)) value = "'" + value;
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }).collect(java.util.stream.Collectors.joining(",")) + "\n";
    }

    private boolean dangerousCsvText(String value) {
        boolean leadingControl = false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) { leadingControl = true; continue; }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) continue;
            return leadingControl || cp == '=' || cp == '+' || cp == '-' || cp == '@';
        }
        return leadingControl;
    }

    private String exportBpSelfMonitorData(Long patientId, org.familyhealthcare.util.HealthDateRange range) {
        List<BpSelfMonitorRecord> list = bpSelfMonitorRecordService.listByPatient(patientId);
        StringBuilder sb = new StringBuilder();
        sb.append(text("Record date,Record time,Measurement type,Systolic pressure (mmHg),Diastolic pressure (mmHg),Blood glucose,Blood glucose unit,Measurement period,Notes\n", "记录日期,记录时间,测量类型,收缩压（mmHg）,舒张压（mmHg）,血糖,血糖单位,测量时段,备注\n"));
        for (BpSelfMonitorRecord r : list) {
            if (!range.contains(r.getRecordDate())) continue;
            sb.append(csvRow(r.getRecordDate(), r.getRecordTime(), label(r.getMeasureType()), r.getSystolicBp(),
                    r.getDiastolicBp(), r.getBloodGlucose(), r.getBgUnit(), label(r.getMeasurePeriod()), r.getRemark()));
        }
        return sb.toString();
    }

    private String nvl(Object v) { return v != null ? v.toString() : ""; }
    private String yn(Boolean v) { return Boolean.TRUE.equals(v) ? text("Yes", "是") : text("No", "否"); }

    private String deidentifyCsv(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)", "$1****$2")
                .replaceAll("(?<![0-9A-Za-z])(\\d{3})\\d{11}([0-9Xx]{4})(?![0-9A-Za-z])", "$1***********$2");
    }
}
