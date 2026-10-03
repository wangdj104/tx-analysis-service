package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.careplan.CareNurseAssignmentService;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@CarePlanExceptionAdvice.Api
@ConditionalOnProperty(name = "care-plan.enabled", havingValue = "true")
@RequestMapping("/care-nurse-assignments")
public class CareNurseAssignmentController {
    private final CareNurseAssignmentService service;
    public CareNurseAssignmentController(CareNurseAssignmentService service) { this.service = service; }

    @GetMapping public Result<List<Map<String,Object>>> list(HttpServletRequest request) {
        long actorId=actor();Map<String,String> query=CarePlanController.query(request,"patientId");
        return Result.ok(service.list(actorId, CarePlanController.positive(query.get("patientId"))));
    }
    @PostMapping public Result<Map<String,Object>> assign(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actorId=actor();CarePlanController.query(request);return Result.ok(service.assign(actorId, body));
    }
    @PostMapping("/{id}/revoke") public Result<Void> revoke(@PathVariable String id, @RequestBody(required = false) Map<String,Object> body,HttpServletRequest request) {
        long actorId = actor();CarePlanController.query(request);
        long assignmentId=CarePlanController.positive(id);
        if (body != null && !body.isEmpty()) throw CarePlanException.invalid("Revoke does not accept request fields.");
        service.revoke(actorId, assignmentId); return Result.ok();
    }
    private long actor() {
        Long id = CurrentUserUtil.getCurrentUserId();
        if (id == null) throw CarePlanException.denied();
        return id;
    }
}
