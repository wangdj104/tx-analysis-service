package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.service.DryWeightMonthlyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dry WeightMonthly recordcontroldevice
 */
@RestController
@RequestMapping("/dry-weight")
@Api(tags = "Dry WeightMonthly management")
public class DryWeightMonthlyController {

    @Autowired
    private DryWeightMonthlyService dryWeightMonthlyService;

    @GetMapping("/list")
    @ApiOperation("queryhas Dry WeightMonthly record")
    public Result<List<DryWeightMonthly>> list(@RequestParam Long patientId) {
        List<DryWeightMonthly> list = dryWeightMonthlyService.listAllOrderByMonth(patientId);
        return Result.ok(list);
    }

    @GetMapping("/get")
    @ApiOperation("getspecifiedMonth Dry Weightreference value")
    public Result<BigDecimal> getByMonth(@RequestParam String yearMonth, @RequestParam Long patientId) {
        BigDecimal dryWeight = dryWeightMonthlyService.getDryWeightByMonth(yearMonth, patientId);
        return Result.ok(dryWeight);
    }

    @PostMapping("/save")
    @ApiOperation("Saveor updateDry Weightrecord")
    public Result<String> save(@RequestBody DryWeightMonthly record) {
        boolean success = dryWeightMonthlyService.saveOrUpdateByMonth(record);
        return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteDry Weightrecord")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = dryWeightMonthlyService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
