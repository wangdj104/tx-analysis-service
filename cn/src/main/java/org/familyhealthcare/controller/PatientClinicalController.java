package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.PatientClinical;
import org.familyhealthcare.service.PatientClinicalService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * PatientclinicalinformationController
 */
@RestController
@RequestMapping("/patient-clinical")
@Api(tags = "Patientclinicalinformationmanagement")
public class PatientClinicalController {

    @Autowired
    private PatientClinicalService patientClinicalService;

    @GetMapping("/detail/{patientId}")
    @ApiOperation("based onPatientIDgetclinicalinformation")
    public Result<PatientClinical> getByPatientId(@PathVariable Long patientId) {
        try {
            PatientClinical record = patientClinicalService.getByPatientId(patientId);
            return Result.ok(record);
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @PostMapping("/save")
    @ApiOperation("Saveor updatePatientclinicalinformation")
    public Result<String> save(@RequestBody PatientClinical record) {
        try {
            boolean success = patientClinicalService.saveOrUpdateClinical(record);
            return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeletePatientclinicalinformation")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean success = patientClinicalService.deleteOwned(id);
            return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }
}
