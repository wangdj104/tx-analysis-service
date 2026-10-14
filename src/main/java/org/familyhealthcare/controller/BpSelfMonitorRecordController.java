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
        } catch (org.familyhealthcare.service.alertworkflow.AlertWorkflowException e) {
            throw e;
        } catch (Exception e) {
            return Result.error("Vital-sign records could not be loaded. Verify that the database is initialized.");
        }
    }

    @PostMapping("/save")
    public Result<?> save(@RequestBody java.util.Map<String,Object> body) {
        try{return Result.ok(bpSelfMonitorRecordService.saveRequest(body));}catch(IllegalStateException denied){return Result.error(403,denied.getMessage());}
    }

    @PutMapping("/update")
    public Result<?> update(@RequestBody java.util.Map<String,Object> body) {
        try {
            java.util.Map<String,Object> result=bpSelfMonitorRecordService.updateRequest(body);
            if(result.containsKey("updated"))return Boolean.TRUE.equals(result.get("updated"))?Result.okMessage("Updated successfully"):Result.error("Update failed");
            return Result.ok(result);
        }catch(IllegalStateException denied){return Result.error(403,denied.getMessage());}
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteBlood GlucoseBlood Pressurerecord")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = bpSelfMonitorRecordService.deleteOwned(id);
            return success ? Result.okMessage("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
