package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.util.MeasurementInputNumbers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

import static org.familyhealthcare.util.ExportLocalization.text;

/** Independent row boundary. Call through the Spring proxy so success means a committed row. */
@Service
public class ClinicalImportRowWriter {
    @Autowired private BpSelfMonitorRecordService vitalService;
    @Autowired private BpSelfMonitorRecordMapper vitalMapper;
    @Autowired private MedicalRecordService medicalRecordService;
    @Autowired private MedicalRecordMapper medicalRecordMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean write(Long patientId, String patientName, ClinicalImportFacts.PreparedRow prepared) {
        Map<String,Object> item = prepared.fields();
        ClinicalImportFacts facts = prepared.facts();
        if (exists(patientId, item)) return false;
        String kind = String.valueOf(item.get("kind"));
        String source = String.valueOf(item.get("sourceType"));
        String externalId = string(item.get("externalId"));
        boolean saved;
        if ("VITAL".equals(kind)) {
            String measureType = String.valueOf(item.get("measureType"));
            boolean requiresBp = "BP".equals(measureType) || "BOTH".equals(measureType) || "BP_BG".equals(measureType);
            // 重复身份先跳过；非重复行在原生写入前再次校验，纯血糖行也不能丢弃坏血压。
            Integer systolic = requiresBp || item.containsKey("systolic")
                    ? MeasurementInputNumbers.requireImportInteger(item.get("systolic"), "systolic") : null;
            Integer diastolic = requiresBp || item.containsKey("diastolic")
                    ? MeasurementInputNumbers.requireImportInteger(item.get("diastolic"), "diastolic") : null;
            BpSelfMonitorRecord row = new BpSelfMonitorRecord();
            row.setPatientId(patientId); row.setRecordDate(LocalDate.parse(String.valueOf(item.get("date"))));
            row.setRecordTime(string(item.get("time"))); row.setMeasureType(String.valueOf(item.get("measureType")));
            row.setSystolicBp(systolic); row.setDiastolicBp(diastolic);
            row.setBloodGlucose(decimal(item.get("glucose"))); row.setBgUnit(string(item.get("unit")));
            row.setSourceType(source); row.setSourceExternalId(externalId);
            row.setVerificationStatus("REVIEW_REQUIRED"); row.setRemark(text("Imported data; verify against the source device or report.", "导入数据，请与原始设备或报告核对。"));
            facts.requireBpProjection(row);
            saved = vitalService.saveImportedOwned(row, facts);
        } else if ("LAB".equals(kind)) {
            MedicalRecord record = new MedicalRecord();
            record.setPatientId(patientId); record.setPatientName(patientName);
            record.setRecordDate(LocalDate.parse(String.valueOf(item.get("date")))); record.setRecordType("OTHER");
            record.setHospitalName(string(item.get("organization")) == null ? text("Imported clinical data", "导入的临床数据") : string(item.get("organization")));
            record.setSourceType(source); record.setSourceExternalId(externalId);
            record.setVerificationStatus("REVIEW_REQUIRED"); record.setRemark(text("Imported data; verify before clinical use.", "导入数据，临床使用前请核验。"));
            MedicalRecordItem detail = new MedicalRecordItem();
            detail.setItemCode(retainedText(item.get("code"))); detail.setItemName(String.valueOf(item.get("display")));
            detail.setResultValue(String.valueOf(item.get("value"))); detail.setUnit(retainedText(item.get("unit")));
            detail.setReferenceRange(string(item.get("referenceRange"))); detail.setAiConfidence(decimal(item.get("confidence")));
            facts.requireLabProjection(record, detail);
            saved = medicalRecordService.saveImportedRecordWithItem(record, detail, facts);
        } else {
            throw new IllegalArgumentException(text("Unsupported clinical import item.", "不支持的临床导入项目。"));
        }
        if (!saved) throw new IllegalStateException(text("The clinical import row could not be saved.", "临床导入记录未能保存。"));
        return true;
    }

    /** Exact patient/source/external-id identity, also used after a unique-key race rolls back. */
    public boolean exists(Long patientId, Map<String,Object> item) {
        String externalId = string(item.get("externalId"));
        if (externalId == null) return false;
        String source = String.valueOf(item.get("sourceType"));
        if ("VITAL".equals(item.get("kind"))) {
            return vitalMapper.selectCount(new QueryWrapper<BpSelfMonitorRecord>()
                    .eq("patient_id", patientId).eq("source_type", source).eq("source_external_id", externalId)) > 0;
        }
        if ("LAB".equals(item.get("kind"))) {
            return medicalRecordMapper.selectCount(new QueryWrapper<MedicalRecord>()
                    .eq("patient_id", patientId).eq("source_type", source).eq("source_external_id", externalId)) > 0;
        }
        return false;
    }

    /** 原始检验编码和单位仅把空白视为缺失，字面文本 null 仍是来源事实。 */
    private String retainedText(Object value) { if (value == null) return null; String text = String.valueOf(value).trim(); return text.isEmpty() ? null : text; }
    private String string(Object value) { if (value == null) return null; String text = String.valueOf(value).trim(); return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text; }
    private BigDecimal decimal(Object value) { try { return value == null ? null : new BigDecimal(String.valueOf(value)); } catch (NumberFormatException e) { return null; } }
}
