package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.service.BpSelfMonitorRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bp-self-monitor")
@Api(tags = "Blood GlucoseBlood Pressureself-monitoring")
public class BpSelfMonitorRecordController {

    @Autowired
    private BpSelfMonitorRecordService bpSelfMonitorRecordService;

    @GetMapping("/list")
    @ApiOperation("queryBlood GlucoseBlood Pressurerecordlist")
    public Result<List<BpSelfMonitorRecord>> list(
            @RequestParam Long patientId,
            @RequestParam(required = false) String measureType) {
        try {
            if (measureType != null) {
                return Result.ok(bpSelfMonitorRecordService.listByPatientAndType(patientId, measureType));
            }
            return Result.ok(bpSelfMonitorRecordService.listByPatient(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            return Result.error("Vital-sign records could not be loaded. Verify that the database is initialized.");
        }
    }

    @PostMapping("/save")
    @ApiOperation("AddBlood GlucoseBlood Pressurerecord")
    public Result<BpSelfMonitorRecord> save(@RequestBody BpSelfMonitorRecord record) {
        try {
            boolean success = bpSelfMonitorRecordService.saveOwned(record);
            return success ? Result.ok(record) : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/update")
    @ApiOperation("updateBlood GlucoseBlood Pressurerecord")
    public Result<String> update(@RequestBody BpSelfMonitorRecord record) {
        try {
            boolean success = bpSelfMonitorRecordService.updateOwned(record);
            return success ? Result.ok("Updated successfully") : Result.error("Update failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteBlood GlucoseBlood Pressurerecord")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = bpSelfMonitorRecordService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
