package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.DashboardService;
import org.familyhealthcare.vo.DashboardSummaryVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
@Api(tags = "firstpagedevicetablepanel")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @GetMapping("/summary")
    @ApiOperation("firstpagesummarizedata")
    public Result<DashboardSummaryVO> summary(@RequestParam(required = false) Long patientId) {
        try {
            return Result.ok(dashboardService.getSummary(patientId));
        } catch (IllegalStateException e) {
            return Result.error(401, e.getMessage());
        }
    }
}
