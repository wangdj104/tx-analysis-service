package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordAttachment;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.util.PdfConvertResult;
import org.familyhealthcare.service.AiOcrService;
import org.familyhealthcare.service.MedicalRecordService;
import org.familyhealthcare.service.PatientService;
import org.familyhealthcare.util.PdfToImageUtil;
import org.familyhealthcare.util.WordExtractUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Medical RecordsController
 */
@RestController
@RequestMapping("/medical-record")
@Api(tags = "Medical Recordsmanagement")
public class MedicalRecordController {

    @Autowired
    private MedicalRecordService medicalRecordService;

    @Autowired
    private AiOcrService aiOcrService;

    @Autowired
    private PatientService patientService;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @PostMapping("/upload")
    @ApiOperation("UploadExaminationReportandAIrecognition (supportimage/PDF/Word) ")
    public Result<Map<String, Object>> uploadAndRecognize(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "patientId", required = false) Long patientId,
            @RequestParam(value = "recordType", required = false) String recordType) {
        try {
            if (files == null || files.length == 0) {
                return Result.error("Upload at least one file.");
            }
            if (files.length > 10) {
                return Result.error("Upload no more than 10 files at a time.");
            }

            // based onpatientIdgetPatientName
            String patientName = null;
            if (patientId != null) {
                Patient patient = patientService.getOwnedById(patientId);
                if (patient != null) {
                    patientName = patient.getName();
                }
            }

            List<String> base64List = new ArrayList<>();
            List<String> wordTextList = new ArrayList<>();
            List<String> pdfWarnings = new ArrayList<>();

            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename();
                if (originalFilename == null) continue;

                String lowerName = originalFilename.toLowerCase();

                // processPDFfile
                if (lowerName.endsWith(".pdf")) {
                    PdfConvertResult pdfResult = PdfToImageUtil.pdfToBase64ImagesWithMeta(
                            file.getInputStream(), originalFilename);
                    base64List.addAll(pdfResult.getBase64Images());
                    String pdfWarn = pdfResult.buildWarningMessage();
                    if (pdfWarn != null) {
                        pdfWarnings.add(originalFilename + ": " + pdfWarn);
                    }
                }
                // processWordfile
                else if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) {
                    String text = WordExtractUtil.extractText(
                        file.getInputStream(), originalFilename);
                    wordTextList.add(text);
                }
                // processimagefile
                else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                         lowerName.endsWith(".png") || lowerName.endsWith(".bmp")) {
                    base64List.add(Base64.getEncoder().encodeToString(file.getBytes()));
                } else {
                    return Result.error("Unsupported file format: " + originalFilename);
                }
            }

            // ifhas Wordtext, needneedspecialprocess
            if (!wordTextList.isEmpty() && base64List.isEmpty()) {
                return Result.error("Word recognition is not supported yet. Upload an image or PDF.");
            }

            if (base64List.isEmpty()) {
                return Result.error("No recognizable image was found.");
            }

            Map<String, Object> result = aiOcrService.recognizeMedicalReport(base64List, recordType);

            if (!pdfWarnings.isEmpty()) {
                Map<String, Object> pdfMeta = new HashMap<>();
                pdfMeta.put("warnings", pdfWarnings);
                pdfMeta.put("maxPages", PdfToImageUtil.getMaxPages());
                result.put("pdfMeta", pdfMeta);
                String joined = String.join(" ", pdfWarnings);
                Object existingWarn = result.get("warning");
                String warn = existingWarn != null ? existingWarn + " " + joined : joined;
                result.put("warning", warn);
            }

            if (patientName != null) {
                result.put("patientName", patientName);
            }
            if (patientId != null) {
                result.put("patientId", patientId);
            }

            return toOcrResult(result);
        } catch (IOException e) {
            return Result.error("Failed to read file: " + e.getMessage());
        } catch (RuntimeException e) {
            return Result.error("Failed to parse file: " + e.getMessage());
        }
    }

    @PostMapping("/recognize-base64")
    @ApiOperation("Base64imagerecognition")
    public Result<Map<String, Object>> recognizeBase64(
            @RequestBody Map<String, String> params) {
        String base64 = params.get("base64Image");
        String recordType = params.get("recordType");
        if (base64 == null || base64.isEmpty()) {
            return Result.error("Image data cannot be empty");
        }
        Map<String, Object> result = aiOcrService.recognizeMedicalReport(base64, recordType);
        return toOcrResult(result);
    }

    private Result<Map<String, Object>> toOcrResult(Map<String, Object> result) {
        if (result != null && result.get("error") != null) {
            return Result.error(String.valueOf(result.get("error")));
        }
        return Result.ok(result);
    }

    @PostMapping("/save-batch")
    @ApiOperation("batchSaveMedical Records (multipleExamination Typesplit) ")
    public Result<String> saveBatch(@RequestBody Map<String, Object> params) {
        try {
            List<MedicalRecord> records = JSON.parseArray(
                    JSON.toJSONString(params.get("records")), MedicalRecord.class);
            if (records == null || records.isEmpty()) {
                return Result.error("There are no records to save.");
            }
            List<List<MedicalRecordItem>> itemsList = new ArrayList<>();
            Object itemsParam = params.get("itemsList");
            if (itemsParam != null) {
                JSONArray arr = JSON.parseArray(JSON.toJSONString(itemsParam));
                for (int i = 0; i < arr.size(); i++) {
                    itemsList.add(JSON.parseArray(arr.getJSONArray(i).toJSONString(), MedicalRecordItem.class));
                }
            }
            List<MedicalRecordAttachment> attachments = null;
            if (params.get("attachments") != null) {
                attachments = JSON.parseArray(
                        JSON.toJSONString(params.get("attachments")), MedicalRecordAttachment.class);
            }
            int count = medicalRecordService.saveRecordsBatch(records, itemsList, attachments);
            return Result.ok("Saved " + count + " records");
        } catch (Exception e) {
            return Result.error("Failed to save: " + e.getMessage());
        }
    }

    @PostMapping("/save")
    @ApiOperation("SaveMedical Records")
    public Result<String> save(@RequestBody Map<String, Object> params) {
        try {
            MedicalRecord record = JSON.parseObject(JSON.toJSONString(params.get("record")), MedicalRecord.class);
            List<MedicalRecordItem> items = JSON.parseArray(
                    JSON.toJSONString(params.get("items")), MedicalRecordItem.class);
            record.setAttachments(parseAttachments(params.get("attachments")));

            boolean success = medicalRecordService.saveRecordWithItems(record, items);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (Exception e) {
            return Result.error("Failed to save: " + e.getMessage());
        }
    }

    @PutMapping("/update")
    @ApiOperation("updateMedical Records")
    public Result<String> update(@RequestBody Map<String, Object> params) {
        try {
            MedicalRecord record = JSON.parseObject(JSON.toJSONString(params.get("record")), MedicalRecord.class);
            List<MedicalRecordItem> items = JSON.parseArray(
                    JSON.toJSONString(params.get("items")), MedicalRecordItem.class);
            record.setAttachments(parseAttachments(params.get("attachments")));

            boolean success = medicalRecordService.updateRecordWithItems(record, items);
            return success ? Result.ok("Updated successfully") : Result.error("Update failed");
        } catch (Exception e) {
            return Result.error("Update failed: " + e.getMessage());
        }
    }

    private List<MedicalRecordAttachment> parseAttachments(Object attachmentsParam) {
        if (attachmentsParam == null) {
            return null;
        }
        return JSON.parseArray(JSON.toJSONString(attachmentsParam), MedicalRecordAttachment.class);
    }

    @GetMapping("/list")
    @ApiOperation("queryMedical Recordslist")
    public Result<List<MedicalRecord>> list(
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String timeValue) {
        try {
            List<MedicalRecord> list = medicalRecordService.listRecords(patientId, patientName, recordType, timeValue);
            return Result.ok(list);
        } catch (IllegalStateException e) {
            return Result.error(401, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @ApiOperation("based onIDqueryrecordDetails")
    public Result<MedicalRecord> getById(@PathVariable Long id) {
        MedicalRecord record = medicalRecordService.getRecordWithDetails(id);
        return Result.ok(record);
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteMedical Records")
    public Result<String> delete(@PathVariable Long id) {
        boolean success = medicalRecordService.deleteRecord(id);
        return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
    }

    @GetMapping("/trend")
    @ApiOperation("querysomeitemExaminationindicator historytrend")
    public Result<List<Map<String, Object>>> getItemTrend(
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String patientName,
            @RequestParam String itemName) {
        try {
            List<Map<String, Object>> trend = medicalRecordService.getItemTrend(patientId, patientName, itemName);
            return Result.ok(trend);
        } catch (IllegalStateException e) {
            return Result.error(401, e.getMessage());
        }
    }

    @GetMapping("/items")
    @ApiOperation("queryExaminationitemNamelist")
    public Result<List<String>> getAllItemNames(@RequestParam(required = false) Long patientId) {
        List<String> itemNames = medicalRecordService.getAllItemNames(patientId);
        return Result.ok(itemNames);
    }
}