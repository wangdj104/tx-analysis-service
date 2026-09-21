package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.util.PrivacyMaskUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * privacy protectionController
 * raiseprovidemaskdataqueryAPI and masklevelconfiguration
 */
@RestController
@RequestMapping("/privacy")
@Api(tags = "privacy protection")
public class PrivacyProtectionController {

    @Autowired
    private PatientMapper patientMapper;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    /**
     * getmaskafter  Patientinformation (used forDaylog/auditetc.context)
     */
    @GetMapping("/masked-patient/{id}")
    @ApiOperation("getmaskafter  Patientbasethis information")
    public Result<Map<String, String>> getMaskedPatient(@PathVariable Long id) {
        try {
            Patient patient = dataScopeHelper.requirePatient(id);
            Map<String, String> masked = new HashMap<>();
            masked.put("id", String.valueOf(patient.getId()));
            masked.put("name", PrivacyMaskUtil.maskName(patient.getName()));
            masked.put("phone", PrivacyMaskUtil.maskPhone(patient.getPhone()));
            masked.put("idCard", PrivacyMaskUtil.maskIdCard(patient.getIdCard()));
            masked.put("address", PrivacyMaskUtil.maskAddress(patient.getAddress()));
            masked.put("emergencyContact", PrivacyMaskUtil.maskName(patient.getEmergencyContact()));
            masked.put("emergencyPhone", PrivacyMaskUtil.maskEmergencyPhone(patient.getEmergencyPhone()));
            return Result.ok(masked);
        } catch (IllegalStateException e) {
            return Result.error(403, e.getMessage());
        }
    }

    /**
     * getcurrentmaskstrategyinstructions
     */
    @GetMapping("/policy")
    @ApiOperation("getmaskstrategyinstructions")
    public Result<Map<String, String>> getPrivacyPolicy() {
        Map<String, String> policy = new HashMap<>();
        policy.put("name", "Name: keepfirstendcharacter, separated by*replace");
        policy.put("phone", "Phone Number: keep the first3after 4, separated by*replace");
        policy.put("idCard", "ID Number: keep the first3after 4, separated by*replace");
        policy.put("address", "Address: keep the first6character, itsremaininguse*replace");
        policy.put("emergencyContact", "Urgentcontact: sameNamemaskrule");
        policy.put("emergencyPhone", "UrgentPhone Number: samePhone Numbermaskrule");
        policy.put("note", "maskpolicy appliesused forExport, Daylog, auditetc.non-corebusinesscontext; corebusinessinterface (Patient Management) toauthorizeuserdisplaycompletedata");
        return Result.ok(policy);
    }
}
