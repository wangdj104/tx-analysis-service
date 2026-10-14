package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
/** Safe recipient status and token-conditioned current-admin retry, independent of clinical authority. */
@RestController
@AlertWorkflowExceptionAdvice.Api
@RequestMapping("/alert-workflows")
public class AlertWorkflowNotificationController {
    private final AlertWorkflowProperties properties;private final ObjectProvider<AlertWorkflowNotificationWorker> worker;private final AlertWorkflowAuthorizationService auth;
    public AlertWorkflowNotificationController(AlertWorkflowProperties properties,ObjectProvider<AlertWorkflowNotificationWorker> worker,AlertWorkflowAuthorizationService auth){this.properties=properties;this.worker=worker;this.auth=auth;}
    @GetMapping("/{id}/delivery-status") public Result<Map<String,Object>> status(@PathVariable String id,HttpServletRequest request){long actor=actor();Map<String,String> q=AlertWorkflowController.query(request,"cursor","limit");long limit=q.containsKey("limit")?AlertWorkflowController.positive(q.get("limit")):50;if(limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");return Result.ok(worker.getObject().deliveryStatus(actor,AlertWorkflowController.positive(id),q.get("cursor"),(int)limit));}
    @PostMapping("/notifications/{id}/retry") public Result<Map<String,Object>> retry(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request){long actor=actor();AlertWorkflowController.query(request);AlertWorkflowContracts.fields(body,new HashSet<>(Arrays.asList("retryToken","duplicateRiskAcknowledged")));if(!(body.get("retryToken") instanceof String)||!((String)body.get("retryToken")).matches("[0-9a-f]{64}")||!(body.get("duplicateRiskAcknowledged") instanceof Boolean))throw AlertWorkflowException.invalid("retryToken and a boolean duplicateRiskAcknowledged are required.");return Result.ok(worker.getObject().retry(actor,AlertWorkflowController.positive(id),(String)body.get("retryToken"),(Boolean)body.get("duplicateRiskAcknowledged")));}
    private long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null||actor<=0)throw AlertWorkflowException.denied();properties.requireEnabled();auth.requireActiveActor(actor);return actor;}
}
