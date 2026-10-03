package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.careplan.CareNurseAssignmentService;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CarePlanExceptionAdvice.Api
@ConditionalOnProperty(name = "care-plan.enabled", havingValue = "true")
@RequestMapping("/care-nurse-assignments")
public class CareNurseAssignmentController {
    private final CareNurseAssignmentService service;
    public CareNurseAssignmentController(CareNurseAssignmentService service) { this.service = service; }

    @GetMapping public Result<List<Map<String,Object>>> list(@RequestParam long patientId) {
        return Result.ok(service.list(actor(), patientId));
    }
    @PostMapping public Result<Map<String,Object>> assign(@RequestBody Map<String,Object> body) {
        return Result.ok(service.assign(actor(), body));
    }
    @PostMapping("/{id}/revoke") public Result<Void> revoke(@PathVariable long id, @RequestBody(required = false) Map<String,Object> body) {
        long actorId = actor();
        if (body != null && !body.isEmpty()) throw CarePlanException.invalid("撤销操作不接受请求字段。");
        service.revoke(actorId, id); return Result.ok();
    }
    private long actor() {
        Long id = CurrentUserUtil.getCurrentUserId();
        if (id == null) throw CarePlanException.denied();
        return id;
    }
}
