package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.ClinicalWorkbenchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/clinical-workbench")
public class ClinicalWorkbenchController {
    @Autowired private ClinicalWorkbenchService service;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam Long patientId) { return Result.ok(service.overview(patientId)); }

    @GetMapping("/attention")
    public Result<Map<String, Object>> attention(@RequestParam Long patientId) { return Result.ok(service.attention(patientId)); }

    @GetMapping("/data-quality")
    public Result<Map<String, Object>> dataQuality(@RequestParam Long patientId) { return Result.ok(service.dataQuality(patientId)); }

    @GetMapping("/medication-safety")
    public Result<Map<String, Object>> medicationSafety(@RequestParam Long patientId) { return Result.ok(service.medicationSafety(patientId)); }

    @GetMapping("/dialysis-quality")
    public Result<Map<String, Object>> dialysisQuality(@RequestParam Long patientId,@RequestParam(defaultValue="90") int days) { return Result.ok(service.dialysisQuality(patientId, days)); }

    @GetMapping("/emergency-card")
    public Result<Map<String, Object>> emergencyCard(@RequestParam Long patientId) { return Result.ok(service.emergencyCard(patientId)); }
}
