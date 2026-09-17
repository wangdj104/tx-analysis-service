package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.BpPatternAnalysis;
import org.familyhealthcare.service.BpPatternAnalysisService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bp-pattern")
@Api(tags = "Blood Pressure Pattern Analysis")
public class BpPatternController {

    @Autowired
    private BpPatternAnalysisService bpPatternAnalysisService;

    @PostMapping("/analyze")
    @ApiOperation("triggerBlood Pressure Pattern Analysis")
    public Result<BpPatternAnalysis> analyze(
            @RequestParam Long patientId,
            @RequestParam(required = false) String timeType,
            @RequestParam(required = false) String timeValue) {
        try {
            return Result.ok(bpPatternAnalysisService.analyze(patientId, timeType, timeValue));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @GetMapping("/list")
    @ApiOperation("queryBlood Pressureanalysisrecordlist")
    public Result<List<BpPatternAnalysis>> list(@RequestParam Long patientId) {
        try {
            return Result.ok(bpPatternAnalysisService.listByPatient(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteBlood Pressureanalysisrecord")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = bpPatternAnalysisService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
