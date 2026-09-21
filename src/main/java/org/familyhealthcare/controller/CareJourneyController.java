package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.CareJourneyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Complete cross-role care journey API. */
@RestController
@RequestMapping("/care-journey")
public class CareJourneyController {
    @Autowired private CareJourneyService service;

    @GetMapping("/measurements") public Result<List<Map<String,Object>>> measurements(@RequestParam Long patientId,@RequestParam(required=false)String metricType,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)LocalDateTime from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)LocalDateTime to){return Result.ok(service.measurements(patientId,metricType,from,to));}
    @PostMapping("/measurements") public Result<Map<String,Object>> saveMeasurement(@RequestBody Map<String,Object> body){return Result.ok(service.saveMeasurement(body));}
    @PostMapping("/measurements/{id}/annotations") public Result<Map<String,Object>> annotate(@PathVariable Long id,@RequestBody Map<String,String>body){return Result.ok(service.annotateMeasurement(id,body.get("annotation")));}

    @GetMapping("/access-grants") public Result<List<Map<String,Object>>> grants(@RequestParam Long patientId){return Result.ok(service.grants(patientId));}
    @PostMapping("/access-grants") public Result<Map<String,Object>> saveGrant(@RequestBody Map<String,Object>body){return Result.ok(service.saveGrant(body));}
    @DeleteMapping("/access-grants/{id}") public Result<String> revokeGrant(@PathVariable Long id){service.revokeGrant(id);return Result.ok("Access revoked.");}

    @GetMapping("/doctor-schedules") public Result<List<Map<String,Object>>> schedules(@RequestParam(required=false)Long doctorUserId,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return Result.ok(service.doctorSchedules(doctorUserId,from,to));}
    @GetMapping("/clinicians") public Result<List<Map<String,Object>>> clinicians(){return Result.ok(service.clinicians());}
    @PostMapping("/doctor-schedules") public Result<Map<String,Object>> saveSchedule(@RequestBody Map<String,Object>body){return Result.ok(service.saveDoctorSchedule(body));}
    @GetMapping("/appointments") public Result<List<Map<String,Object>>> appointments(@RequestParam Long patientId){return Result.ok(service.appointments(patientId));}
    @PostMapping("/appointments") public Result<Map<String,Object>> saveAppointment(@RequestBody Map<String,Object>body){return Result.ok(service.saveAppointment(body));}
    @PostMapping("/appointments/{id}/cancel") public Result<String> cancelAppointment(@PathVariable Long id,@RequestBody(required=false)Map<String,String>body){service.cancelAppointment(id,body==null?null:body.get("reason"));return Result.ok("Appointment cancelled.");}

    @GetMapping("/visits") public Result<List<Map<String,Object>>> visits(@RequestParam Long patientId){return Result.ok(service.visits(patientId));}
    @PostMapping("/visits") public Result<Map<String,Object>> saveVisit(@RequestBody Map<String,Object>body){return Result.ok(service.saveVisit(body));}
    @PostMapping("/visits/{id}/publish") public Result<Map<String,Object>> publishVisit(@PathVariable Long id){return Result.ok(service.publishVisit(id));}
    @PostMapping("/prescriptions") public Result<Map<String,Object>> savePrescription(@RequestBody Map<String,Object>body){return Result.ok(service.savePrescription(body));}

    @GetMapping("/consultations") public Result<List<Map<String,Object>>> consultations(@RequestParam Long patientId){return Result.ok(service.consultations(patientId));}
    @PostMapping("/consultations") public Result<Map<String,Object>> startConsultation(@RequestBody Map<String,Object>body){return Result.ok(service.startConsultation(body));}
    @GetMapping("/consultations/{id}") public Result<Map<String,Object>> consultation(@PathVariable Long id){return Result.ok(service.consultation(id));}
    @PostMapping("/consultations/{id}/messages") public Result<Map<String,Object>> message(@PathVariable Long id,@RequestBody Map<String,Object>body){return Result.ok(service.addConsultationMessage(id,body));}
    @GetMapping("/consultations/{id}/signals") public Result<List<Map<String,Object>>> signals(@PathVariable Long id,@RequestParam(defaultValue="0")Long afterId){return Result.ok(service.consultationSignals(id,afterId));}
    @PostMapping("/consultations/{id}/signals") public Result<Map<String,Object>> signal(@PathVariable Long id,@RequestBody Map<String,Object>body){return Result.ok(service.sendConsultationSignal(id,body));}
    @PostMapping("/consultations/{id}/close") public Result<Map<String,Object>> closeConsultation(@PathVariable Long id,@RequestBody(required=false)Map<String,Object>body){return Result.ok(service.closeConsultation(id,body==null?java.util.Collections.emptyMap():body));}

    @GetMapping("/treatment-plans") public Result<List<Map<String,Object>>> treatmentPlans(@RequestParam Long patientId){return Result.ok(service.treatmentPlans(patientId));}
    @PostMapping("/treatment-plans") public Result<Map<String,Object>> saveTreatmentPlan(@RequestBody Map<String,Object>body){return Result.ok(service.saveTreatmentPlan(body));}
    @GetMapping("/rehab-checkins") public Result<List<Map<String,Object>>> rehab(@RequestParam Long patientId){return Result.ok(service.rehabCheckins(patientId));}
    @PostMapping("/rehab-checkins") public Result<Map<String,Object>> saveRehab(@RequestBody Map<String,Object>body){return Result.ok(service.saveRehabCheckin(body));}

    @GetMapping("/emergency-card") public Result<Map<String,Object>> emergencyCard(@RequestParam Long patientId){return Result.ok(service.emergencyCard(patientId));}
    @PostMapping("/emergencies") public Result<Map<String,Object>> emergency(@RequestBody Map<String,Object>body){return Result.ok(service.triggerEmergency(body));}

    @GetMapping("/specialty/{type}") public Result<List<Map<String,Object>>> specialty(@PathVariable String type,@RequestParam Long patientId){return Result.ok(service.specialty(type,patientId));}
    @PostMapping("/specialty/{type}") public Result<Map<String,Object>> saveSpecialty(@PathVariable String type,@RequestBody Map<String,Object>body){return Result.ok(service.saveSpecialty(type,body));}
    @GetMapping("/mental-assessments") public Result<List<Map<String,Object>>> mental(@RequestParam Long patientId){return Result.ok(service.mentalAssessments(patientId));}
    @PostMapping("/mental-assessments") public Result<Map<String,Object>> saveMental(@RequestBody Map<String,Object>body){return Result.ok(service.saveMentalAssessment(body));}
    @GetMapping("/mental-schedules") public Result<List<Map<String,Object>>> mentalSchedules(@RequestParam Long patientId){return Result.ok(service.mentalSchedules(patientId));}
    @PostMapping("/mental-schedules") public Result<Map<String,Object>> saveMentalSchedule(@RequestBody Map<String,Object>body){return Result.ok(service.saveMentalSchedule(body));}

    @GetMapping("/patient-groups") public Result<List<Map<String,Object>>> groups(){return Result.ok(service.patientGroups());}
    @PostMapping("/patient-groups") public Result<Map<String,Object>> saveGroup(@RequestBody Map<String,Object>body){return Result.ok(service.savePatientGroup(body));}
    @PostMapping("/patient-groups/{id}/patients/{patientId}") public Result<String> groupMember(@PathVariable Long id,@PathVariable Long patientId){service.addGroupMember(id,patientId);return Result.ok("Patient added to group.");}
    @GetMapping("/operations") public Result<Map<String,Object>> operations(@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){return Result.ok(service.operations(from,to));}
}
