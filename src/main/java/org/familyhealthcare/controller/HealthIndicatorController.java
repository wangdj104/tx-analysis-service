package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.HealthIndicator;
import org.familyhealthcare.service.HealthIndicatorService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * healthindicator dictionaryController
 */
@RestController
@RequestMapping("/health-indicator")
@Api(tags = "healthindicator dictionary")
public class HealthIndicatorController {

    @Autowired
    private HealthIndicatorService healthIndicatorService;

    @GetMapping("/list")
    @ApiOperation("gethas active indicators")
    public Result<List<HealthIndicator>> list() {
        return Result.ok(healthIndicatorService.listActive());
    }

    @GetMapping("/list-by-category")
    @ApiOperation("by categorygetactive indicators")
    public Result<List<HealthIndicator>> listByCategory(@RequestParam String category) {
        return Result.ok(healthIndicatorService.listByCategory(category));
    }

    @GetMapping("/resolve-name")
    @ApiOperation("based oninputNameparsefor standarditemCode")
    public Result<String> resolveName(@RequestParam String name) {
        String itemCode = healthIndicatorService.resolveName(name);
        if (itemCode != null) {
            return Result.ok(itemCode);
        }
        return Result.ok(null);
    }

    @GetMapping("/detail/{itemCode}")
    @ApiOperation("based onitemCodegetindicatorDetails")
    public Result<HealthIndicator> findByCode(@PathVariable String itemCode) {
        HealthIndicator indicator = healthIndicatorService.findByCode(itemCode);
        return Result.ok(indicator);
    }
}
