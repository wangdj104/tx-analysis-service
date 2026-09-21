package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.AiAnalysisRecord;
import org.familyhealthcare.entity.HealthAnalysisAutomation;
import org.familyhealthcare.service.HealthAnalysisAutomationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/health-analysis/automations")
public class HealthAnalysisAutomationController {
    @Autowired private HealthAnalysisAutomationService service;
    @GetMapping public Result<List<HealthAnalysisAutomation>> list(){return Result.ok(service.list());}
    @PostMapping public Result<HealthAnalysisAutomation> save(@RequestBody HealthAnalysisAutomation row){return Result.ok(service.save(row));}
    @DeleteMapping("/{id}") public Result<String> delete(@PathVariable Long id){service.delete(id);return Result.ok("Deleted");}
    @PostMapping("/{id}/run") public Result<AiAnalysisRecord> run(@PathVariable Long id){return Result.ok(service.runNow(id));}
    @PostMapping("/analysis/{id}/review") public Result<AiAnalysisRecord> review(@PathVariable Long id,@RequestBody Map<String,Object> body){
        boolean approved=Boolean.parseBoolean(String.valueOf(body.getOrDefault("approved",false)));
        boolean notify=Boolean.parseBoolean(String.valueOf(body.getOrDefault("notify",false)));
        return Result.ok(service.reviewAnalysis(id,approved,notify));
    }
}
