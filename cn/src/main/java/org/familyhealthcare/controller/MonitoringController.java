package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.MonitoringService;
import org.familyhealthcare.vo.MonitoringSnapshotVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/monitoring")
@Api(tags = "healthmonitoringcenter")
public class MonitoringController {

    @Autowired
    private MonitoringService monitoringService;

    @GetMapping("/snapshot")
    @ApiOperation("getPatienthealthmonitoringsnapshot")
    public Result<MonitoringSnapshotVO> snapshot(
            @RequestParam Long patientId,
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        try {
            return Result.ok(monitoringService.getSnapshot(patientId, days));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
