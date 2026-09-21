package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.DoctorWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/doctor-workspace")
public class DoctorWorkspaceController {
    @Autowired private DoctorWorkspaceService service;

    @GetMapping("/summary") public Result<Map<String, Object>> summary() { return Result.ok(service.summary()); }
    @GetMapping("/patients") public Result<List<Map<String, Object>>> patients() { return Result.ok(service.patients()); }
    @GetMapping("/reviews") public Result<List<Map<String, Object>>> reviews(@RequestParam(defaultValue = "REVIEW_REQUIRED") String status) { return Result.ok(service.reviews(status)); }
    @GetMapping("/notes") public Result<List<Map<String, Object>>> notes(@RequestParam Long patientId) { return Result.ok(service.notes(patientId)); }
    @GetMapping("/plans") public Result<List<Map<String, Object>>> plans(@RequestParam Long patientId) { return Result.ok(service.plans(patientId)); }
    @PostMapping("/notes") public Result<String> saveNote(@RequestBody Map<String, Object> body) { service.saveNote(body); return Result.ok("临床笔记已保存。"); }
    @PostMapping("/plans") public Result<String> savePlan(@RequestBody Map<String, Object> body) { service.savePlan(body); return Result.ok("照护计划已保存。"); }
    @PostMapping("/reviews") public Result<String> review(@RequestBody Map<String, Object> body) { service.review(body); return Result.ok("复核已完成。"); }
    @PostMapping("/assignments") public Result<String> assign(@RequestBody Map<String, Object> body) { service.assign(body); return Result.ok("医生分配已保存。"); }
}
