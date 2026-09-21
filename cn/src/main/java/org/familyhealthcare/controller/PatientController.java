package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.service.PatientService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/patient")
@Api(tags = "Patient Management")
public class PatientController {

    @Autowired
    private PatientService patientService;

    @GetMapping("/list")
    @ApiOperation("queryPatientlist")
    public Result<List<Patient>> list() {
        return Result.ok(patientService.list());
    }

    @PostMapping("/save")
    @ApiOperation("AddPatient")
    public Result<String> save(@RequestBody Patient patient) {
        patientService.save(patient);
        return Result.ok("Saved successfully");
    }

    @PutMapping("/update")
    @ApiOperation("updatePatient")
    public Result<String> update(@RequestBody Patient patient) {
        try {
            boolean ok = patientService.updateOwned(patient);
            return ok ? Result.ok("Updated successfully") : Result.error("The patient does not exist.");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeletePatient")
    public Result<String> delete(@PathVariable Long id) {
        try {
            boolean ok = patientService.deleteOwned(id);
            return ok ? Result.ok("Deleted successfully") : Result.error("The patient does not exist.");
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @GetMapping("/names")
    @ApiOperation("gethas PatientNamelist")
    public Result<List<Map<String, Object>>> names() {
        return Result.ok(patientService.getPatientNames());
    }
}
