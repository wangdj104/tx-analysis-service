package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.AlertWorkflowNurseGovernance;
import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@AlertWorkflowExceptionAdvice.Api
@ConditionalOnProperty(name = "alert-workflow.enabled", havingValue = "true")
@RequestMapping("/alert-workflows/nurse-assignments")
public class AlertWorkflowNurseController {
    private final AlertWorkflowNurseGovernance service;
    public AlertWorkflowNurseController(AlertWorkflowNurseGovernance service) { this.service = service; }

    @GetMapping public Result<List<Map<String,Object>>> list(HttpServletRequest request) {
        long actorId=actor();Map<String,String> query=AlertWorkflowController.query(request,"patientId");
        return Result.ok(service.list(actorId, AlertWorkflowController.positive(query.get("patientId"))));
    }
    @GetMapping("/candidates") public Result<List<Map<String,Object>>> candidates(HttpServletRequest request) {AlertWorkflowController.query(request);return Result.ok(service.candidates(actor()));}
    @PostMapping public Result<Map<String,Object>> assign(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actorId=actor();AlertWorkflowController.query(request);return Result.ok(service.assign(actorId, body));
    }
    @PostMapping("/{id}/revoke") public Result<Void> revoke(@PathVariable String id, @RequestBody(required = false) Map<String,Object> body,HttpServletRequest request) {
        long actorId = actor();AlertWorkflowController.query(request);
        long assignmentId=AlertWorkflowController.positive(id);
        if (body != null && !body.isEmpty()) throw AlertWorkflowException.invalid("Revoke does not accept request fields.");
        service.revoke(actorId, assignmentId); return Result.ok();
    }
    private long actor() {
        Long id = CurrentUserUtil.getCurrentUserId();
        if (id == null) throw AlertWorkflowException.denied();
        return id;
    }
}
