package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.MedicationReminder;
import org.familyhealthcare.service.MedicationReminderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Medication RemindersmanagementController
 */
@RestController
@RequestMapping("/medication-reminder")
@Api(tags = "Medication Remindersmanagement")
public class MedicationReminderController {

    @Autowired
    private MedicationReminderService medicationReminderService;

    @GetMapping("/list")
    @ApiOperation("queryPatient Reminderlist")
    public Result<List<MedicationReminder>> list(@RequestParam Long patientId) {
        try {
            return Result.ok(medicationReminderService.listByPatient(patientId));
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/save")
    @ApiOperation("Saveor updateReminder")
    public Result<String> save(@RequestBody MedicationReminder record) {
        try {
            boolean success = medicationReminderService.saveOrUpdateReminder(record);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PutMapping("/toggle-enabled/{id}")
    @ApiOperation("switchEnabled/DisabledStatus")
    public Result<String> toggleEnabled(@PathVariable Long id) {
        try {
            boolean success = medicationReminderService.toggleEnabled(id);
            return success ? Result.ok("Operation completed") : Result.error("Operation failed");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteReminder")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = medicationReminderService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
