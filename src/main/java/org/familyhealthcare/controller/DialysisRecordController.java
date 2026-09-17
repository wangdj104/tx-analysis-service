package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.service.DialysisRecordService;
import org.familyhealthcare.service.DryWeightMonthlyService;
import org.familyhealthcare.vo.DialysisStatsVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dialysis RecordsController
 */
@RestController
@RequestMapping("/dialysis")
@Api(tags = "Dialysisdatamanagement")
public class DialysisRecordController {

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Autowired
    private DryWeightMonthlyService dryWeightMonthlyService;

    @GetMapping("/list")
    @ApiOperation("queryDialysis Records, supportby Year/Month/weekFilter")
    public Result<List<DialysisRecord>> list(
            @RequestParam(required = false) String timeType,
            @RequestParam(required = false) String timeValue,
            @RequestParam(required = false) Long patientId) {
        try {
            List<DialysisRecord> list = dialysisRecordService.listByFilter(timeType, timeValue, patientId);
            for (DialysisRecord r : list) {
                if (r.getRecordDate() != null) {
                    String yearMonth = r.getRecordDate().toString().substring(0, 7);
                    BigDecimal dryWeight = dryWeightMonthlyService.getDryWeightByMonth(yearMonth, r.getPatientId());
                    r.setDryWeight(dryWeight);
                }
            }
            return Result.ok(list);
        } catch (IllegalStateException e) {
            return Result.error(401, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @ApiOperation("based onIDquery")
    public Result<DialysisRecord> getById(@PathVariable Long id) {
        try {
            return Result.ok(dialysisRecordService.getOwnedById(id));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/save")
    @ApiOperation("AddDialysis Records")
    public Result<String> save(@RequestBody DialysisRecord record) {
        try {
            boolean success = dialysisRecordService.saveRecord(record);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/update")
    @ApiOperation("updateDialysis Records")
    public Result<String> update(@RequestBody DialysisRecord record) {
        try {
            boolean success = dialysisRecordService.updateRecord(record);
            return success ? Result.ok("Updated successfully") : Result.error("Update failed");
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteDialysis Records")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = dialysisRecordService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @GetMapping("/stats")
    @ApiOperation("getstatisticsanalysisdata")
    public Result<DialysisStatsVO> getStats(
            @RequestParam(required = false) String timeType,
            @RequestParam(required = false) String timeValue,
            @RequestParam(required = false) Long patientId) {
        return Result.ok(dialysisRecordService.getStatistics(timeType, timeValue, patientId));
    }

    @GetMapping("/chart")
    @ApiOperation("getcharttabledata")
    public Result<DialysisStatsVO> getChartData(
            @RequestParam(required = false) String timeType,
            @RequestParam(required = false) String timeValue,
            @RequestParam(required = false) Long patientId) {
        return Result.ok(dialysisRecordService.getChartData(timeType, timeValue, patientId));
    }
}
