package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.Medication;
import org.familyhealthcare.entity.MedicationLog;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.service.AiOcrService;
import org.familyhealthcare.service.MedicationService;
import org.familyhealthcare.service.PatientService;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.util.PdfToImageUtil;
import org.familyhealthcare.util.WordExtractUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * MedicationmanagementController
 */
@RestController
@RequestMapping("/medication")
@Api(tags = "Medicationmanagement")
public class MedicationController {

    @Autowired
    private MedicationService medicationService;

    @Autowired
    private AiOcrService aiOcrService;

    @Autowired
    private PatientService patientService;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    // ==================== Medicationdatabasemanagement ====================

    @GetMapping("/list")
    @ApiOperation("queryMedicationlist")
    public Result<List<Medication>> listMedications(@RequestParam(required = false) Long patientId) {
        List<Medication> list = medicationService.list(patientId);
        return Result.ok(list);
    }

    @GetMapping("/list-active")
    @ApiOperation("queryEnabled Medicationlist")
    public Result<List<Medication>> listActiveMedications(@RequestParam(required = false) Long patientId) {
        List<Medication> list = medicationService.listActiveMedications(patientId);
        return Result.ok(list);
    }

    @PostMapping("/save")
    @ApiOperation("AddMedication")
public Result<String> saveMedication(@RequestBody Medication medication) {
        try {
            Long userId = dataScopeHelper.requireUserId();
            dataScopeHelper.requirePatient(medication.getPatientId());
            medication.setId(null);
            medication.setUserId(userId);
            medication.setIsActive(1);
            boolean success = medicationService.save(medication);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/update")
    @ApiOperation("updateMedication")
    public Result<String> updateMedication(@RequestBody Medication medication) {
        try {
            Medication existing = medicationService.getById(medication.getId());
            if (existing == null) {
                return Result.error("Medication not found");
            }
            dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
            medication.setPatientId(existing.getPatientId());
            medication.setUserId(existing.getUserId());
            boolean success = medicationService.updateById(medication);
            return success ? Result.ok("Updated successfully") : Result.error("Update failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteMedication")
    public Result<String> deleteMedication(@PathVariable Long id) {
        try {
            Medication existing = medicationService.getById(id);
            if (existing == null) {
                return Result.error("Medication not found");
            }
            dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
            boolean success = medicationService.removeById(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/save-batch")
    @ApiOperation("batchSaveMedication")
public Result<String> saveMedicationsBatch(@RequestBody List<Medication> medications) {
        try {
            if (medications == null || medications.isEmpty()) {
                return Result.error("Medication list cannot be empty");
            }
            boolean success = medicationService.saveMedicationsBatch(medications);
            return success ? Result.ok("batchSaved successfully") : Result.error("batchFailed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    // ==================== MedicationOCRrecognition ====================

    @PostMapping("/upload-recognize")
    @ApiOperation("UploadMedicationfileandAIrecognition (supportimage/PDF/Word) ")
    public Result<Map<String, Object>> uploadAndRecognize(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "patientId", required = false) Long patientId) {
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

            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename();
                if (originalFilename == null) continue;

                String lowerName = originalFilename.toLowerCase();

                // processPDFfile
                if (lowerName.endsWith(".pdf")) {
                    List<String> pdfImages = PdfToImageUtil.pdfToBase64Images(
                        file.getInputStream(), originalFilename);
                    base64List.addAll(pdfImages);
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

            Map<String, Object> result = aiOcrService.recognizeMedication(base64List);

            // ifraiseprovidepatientName, Addto resultin
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
    @ApiOperation("Base64Medicationimagerecognition")
    public Result<Map<String, Object>> recognizeBase64(
            @RequestBody Map<String, String> params) {
        String base64 = params.get("base64Image");
        if (base64 == null || base64.isEmpty()) {
            return Result.error("Image data cannot be empty");
        }
        Map<String, Object> result = aiOcrService.recognizeMedication(base64);
        return toOcrResult(result);
    }

    private Result<Map<String, Object>> toOcrResult(Map<String, Object> result) {
        if (result != null && result.get("error") != null) {
            return Result.error(String.valueOf(result.get("error")));
        }
        return Result.ok(result);
    }

    // ==================== medicationrecordmanagement ====================

    @GetMapping("/log/list")
    @ApiOperation("queryPatient medicationrecord")
    public Result<List<MedicationLog>> listLogs(
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) Long patientId) {
        try {
            List<MedicationLog> list;
            if (patientId != null || (patientName != null && !patientName.isEmpty())) {
                list = medicationService.listLogs(patientId, patientName);
            } else {
                list = medicationService.listAllLogs();
            }
            return Result.ok(list);
        } catch (IllegalStateException e) {
            return Result.error(401, e.getMessage());
        }
    }

    @PostMapping("/log/save")
    @ApiOperation("Addmedicationrecord")
    public Result<String> saveLog(@RequestBody MedicationLog log) {
        boolean success = medicationService.saveLog(log);
        return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
    }

    @PutMapping("/log/update")
    @ApiOperation("updatemedicationrecord")
    public Result<String> updateLog(@RequestBody MedicationLog log) {
        boolean success = medicationService.updateLog(log);
        return success ? Result.ok("Updated successfully") : Result.error("Update failed");
    }

    @DeleteMapping("/log/delete/{id}")
    @ApiOperation("Deletemedicationrecord")
    public Result<String> deleteLog(@PathVariable Long id) {
        boolean success = medicationService.deleteLog(id);
        return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
    }
}
