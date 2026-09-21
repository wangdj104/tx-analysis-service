package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.NutritionDiary;
import org.familyhealthcare.service.NutritionDiaryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Nutrition Diary Controller (PatientDayoftenrecord)
 */
@RestController
@RequestMapping("/nutrition-diary")
@Api(tags = "Nutrition Diarymanagement")
public class NutritionDiaryController {

    @Autowired
    private NutritionDiaryService nutritionDiaryService;

    @GetMapping("/list")
    @ApiOperation("queryNutrition Diarylist")
    public Result<List<NutritionDiary>> list(@RequestParam Long patientId) {
        try {
            return Result.ok(nutritionDiaryService.listByPatient(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        } catch (Exception e) {
            return Result.error("Nutrition DiaryFailed to load, Please Confirm nutrition_diary tablestructureUpdated successfully");
        }
    }

    @PostMapping("/save")
    @ApiOperation("Saveor updateNutrition Diary")
    public Result<NutritionDiary> save(@RequestBody NutritionDiary record) {
        try {
            boolean success = nutritionDiaryService.saveOrUpdateRecord(record);
            return success ? Result.ok(record) : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/update")
    @ApiOperation("updateNutrition Diary")
    public Result<String> update(@RequestBody NutritionDiary record) {
        try {
            boolean success = nutritionDiaryService.updateOwned(record);
            return success ? Result.ok("Updated successfully") : Result.error("Update failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @GetMapping("/detail/{id}")
    @ApiOperation("getNutrition DiaryDetails")
    public Result<NutritionDiary> detail(@PathVariable Long id) {
        return Result.ok(nutritionDiaryService.getOwnedById(id));
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteNutrition Diary")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = nutritionDiaryService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
