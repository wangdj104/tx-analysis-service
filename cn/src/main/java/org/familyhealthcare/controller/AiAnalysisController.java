package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.AiAnalysisRecord;
import org.familyhealthcare.service.AiAnalysisService;
import org.familyhealthcare.vo.AiAnalysisResultVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI analysiscontroldevice
 */
@RestController
@RequestMapping("/ai")
@Api(tags = "AI smartanalysis")
public class AiAnalysisController {

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @GetMapping("/analyze")
    @ApiOperation("call DeepSeek analysisDialysisdata")
    public Result<AiAnalysisResultVO> analyze(
            @RequestParam(required = false) String timeType,
            @RequestParam(required = false) String timeValue,
            @RequestParam Long patientId) {
        AiAnalysisResultVO result = aiAnalysisService.analyzeDialysisData(timeType, timeValue, patientId);
        return Result.ok(result);
    }

    @PostMapping("/save")
    @ApiOperation("SaveAIanalysisrecord")
    public Result<Boolean> save(@RequestBody AiAnalysisRecord record) {
        boolean success = aiAnalysisService.saveAnalysis(record);
        return success ? Result.ok(true) : Result.error("Failed to save");
    }

    @GetMapping("/history")
    @ApiOperation("queryAIanalysishistory")
    public Result<List<AiAnalysisRecord>> history(
            @RequestParam(required = false) String timeType,
            @RequestParam(required = false) String timeValue,
            @RequestParam Long patientId) {
        List<AiAnalysisRecord> list = aiAnalysisService.listHistory(timeType, timeValue, patientId);
        return Result.ok(list);
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteAIanalysisrecord")
    public Result<Boolean> delete(@PathVariable Long id) {
        try {
            boolean success = aiAnalysisService.deleteAnalysis(id);
            return success ? Result.ok(true) : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
