package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.ComplicationRecord;
import org.familyhealthcare.service.ComplicationRecordService;
import org.familyhealthcare.vo.ComplicationStatsVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Complication TrackingmanagementController
 */
@RestController
@RequestMapping("/complication")
@Api(tags = "Complication Trackingmanagement")
public class ComplicationRecordController {

    @Autowired
    private ComplicationRecordService complicationRecordService;

    @GetMapping("/list")
    @ApiOperation("querycomplicationrecordlist")
    public Result<List<ComplicationRecord>> list(@RequestParam Long patientId) {
        try {
            return Result.ok(complicationRecordService.listByPatient(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            return Result.error("Complication records could not be loaded. Verify that the database is initialized.");
        }
    }

    @PostMapping("/save")
    @ApiOperation("Addcomplicationrecord")
    public Result<String> save(@RequestBody ComplicationRecord record) {
        try {
            boolean success = complicationRecordService.saveRecord(record);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/update")
    @ApiOperation("updatecomplicationrecord")
    public Result<String> update(@RequestBody ComplicationRecord record) {
        try {
            boolean success = complicationRecordService.updateRecord(record);
            return success ? Result.ok("Updated successfully") : Result.error("Update failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("Deletecomplicationrecord")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = complicationRecordService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @GetMapping("/stats")
    @ApiOperation("getcomplicationstatisticsdata")
    public Result<ComplicationStatsVO> getStats(@RequestParam Long patientId) {
        try {
            return Result.ok(complicationRecordService.getStats(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            return Result.error("Complication statistics could not be loaded. Verify that the database is initialized.");
        }
    }
}
