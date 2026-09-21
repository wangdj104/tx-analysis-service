package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.AlertRecord;
import org.familyhealthcare.entity.AlertRule;
import org.familyhealthcare.entity.AlertEvent;
import org.familyhealthcare.service.AlertService;
import org.familyhealthcare.vo.AlertStatsVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * real-timealertmanagementController
 */
@RestController
@RequestMapping("/alert")
@Api(tags = "real-timealertmanagement")
public class AlertController {

    @Autowired
    private AlertService alertService;

    // ---- rulemanagement ----

    @GetMapping("/rules")
    @ApiOperation("queryalert rulelist")
    public Result<List<AlertRule>> listRules(@RequestParam Long patientId) {
        try {
            return Result.ok(alertService.listRules(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/rule/save")
    @ApiOperation("Savealert rule")
    public Result<String> saveRule(@RequestBody AlertRule rule) {
        try {
            boolean success = alertService.saveRule(rule);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/rule/toggle-enabled/{id}")
    @ApiOperation("switchruleEnabled/Disabled")
    public Result<String> toggleRuleEnabled(@PathVariable Long id) {
        try {
            boolean success = alertService.toggleRuleEnabled(id);
            return success ? Result.ok("Operation completed") : Result.error("Operation failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/rule/delete/{id}")
    @ApiOperation("Deletealert rule")
    public Result<String> deleteRule(@PathVariable Long id) {
        try {
            boolean success = alertService.deleteRule(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    // ---- recordmanagement ----

    @GetMapping("/records")
    @ApiOperation("queryalertrecordlist")
    public Result<List<AlertRecord>> listRecords(
            @RequestParam Long patientId,
            @RequestParam(required = false) String status) {
        try {
            return Result.ok(alertService.listRecords(patientId, status));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/record/acknowledge/{id}")
    @ApiOperation("Confirmalert")
    public Result<String> acknowledge(@PathVariable Long id) {
        try {
            boolean success = alertService.acknowledge(id);
            return success ? Result.ok("Confirmsuccessful") : Result.error("Operation failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/record/resolve/{id}")
    @ApiOperation("resolvealert")
    public Result<String> resolve(@PathVariable Long id, @RequestBody Map<String, String> params) {
        try {
            String note = params.get("handlingNote");
            boolean success = alertService.resolve(id, note);
            return success ? Result.ok("resolvesuccessful") : Result.error("Operation failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @GetMapping("/record/{id}/events")
    @ApiOperation("Query alert status history")
    public Result<List<AlertEvent>> events(@PathVariable Long id) {
        return Result.ok(alertService.listEvents(id));
    }

    @DeleteMapping("/record/delete/{id}")
    @ApiOperation("Deletealertrecord")
    public Result<String> deleteRecord(@PathVariable Long id) {
        try {
            boolean success = alertService.deleteRecord(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    // ---- alertExamination ----

    @PostMapping("/check/{patientId}")
    @ApiOperation("ManualtriggeralertExamination")
    public Result<String> checkThresholds(@PathVariable Long patientId) {
        try {
            alertService.checkThresholds(patientId);
            return Result.ok("alertExaminationcomplete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    // ---- statistics ----

    @GetMapping("/stats")
    @ApiOperation("getalertstatisticsdata")
    public Result<AlertStatsVO> getStats(@RequestParam Long patientId) {
        try {
            return Result.ok(alertService.getStats(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
