package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.NutritionAssessment;
import org.familyhealthcare.service.NutritionAssessmentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/nutrition")
@Api(tags = "Nutrition Assessmentmanagement")
public class NutritionAssessmentController {

    @Autowired
    private NutritionAssessmentService nutritionAssessmentService;

    @GetMapping("/list")
    @ApiOperation("queryNutrition Assessmentlist")
    public Result<List<NutritionAssessment>> list(@RequestParam Long patientId) {
        try {
            return Result.ok(nutritionAssessmentService.listByPatient(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/save")
    @ApiOperation("Saveor updateNutrition Assessment")
    public Result<String> save(@RequestBody NutritionAssessment record) {
        try {
            boolean success = nutritionAssessmentService.saveOrUpdateAssessment(record);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/calculate")
    @ApiOperation("calculateNutritionStatus (not Save, onlyBackcalculateresult) ")
    public Result<NutritionAssessment> calculate(@RequestBody NutritionAssessment record) {
        nutritionAssessmentService.calculateNutritionStatus(record);
        return Result.ok(record);
    }

    @GetMapping("/detail/{id}")
    @ApiOperation("getNutrition AssessmentDetails")
    public Result<NutritionAssessment> detail(@PathVariable Long id) {
        return Result.ok(nutritionAssessmentService.getById(id));
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteNutrition Assessment")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = nutritionAssessmentService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
