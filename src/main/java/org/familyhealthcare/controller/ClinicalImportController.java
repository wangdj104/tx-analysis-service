package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.ClinicalImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/clinical-import")
public class ClinicalImportController {
    @Autowired private ClinicalImportService service;

    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        Long patientId = Long.valueOf(String.valueOf(body.get("patientId")));
        return Result.ok(service.preview(patientId, body));
    }

    @PostMapping("/commit")
    public Result<Map<String, Object>> commit(@RequestBody Map<String, Object> body) {
        if (!Boolean.parseBoolean(String.valueOf(body.getOrDefault("confirmed", false)))) {
            return Result.error(400, "Preview the data and explicitly confirm the import first.");
        }
        Long patientId = Long.valueOf(String.valueOf(body.get("patientId")));
        return Result.ok(service.commit(patientId, body));
    }
}
