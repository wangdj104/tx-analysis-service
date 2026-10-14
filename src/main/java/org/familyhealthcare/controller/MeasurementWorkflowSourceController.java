package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.familyhealthcare.service.alertworkflow.AlertWorkflowContracts.*;

/** Versioned native identity lookup and non-destructive invalidation, independent of alert existence. */
@RestController
@AlertWorkflowExceptionAdvice.Api
@RequestMapping("/alert-workflows/sources")
public class MeasurementWorkflowSourceController {
    private final AlertWorkflowProperties properties;
    private final ObjectProvider<MeasurementWorkflowBridge> bridge;
    public MeasurementWorkflowSourceController(AlertWorkflowProperties properties,ObjectProvider<MeasurementWorkflowBridge> bridge){this.properties=properties;this.bridge=bridge;}
    @GetMapping("/{type}/{id}") public Result<Map<String,Object>> source(@PathVariable String type,@PathVariable String id,HttpServletRequest request) {
        long actor=actor();Map<String,String> query=AlertWorkflowController.query(request,"patientId");
        return Result.ok(bridge.getObject().read(actor,AlertWorkflowController.positive(query.get("patientId")),choice(type,"sourceType",SOURCE_TYPES),AlertWorkflowController.positive(id)));
    }
    @GetMapping("/{type}/{id}/revisions") public Result<Map<String,Object>> history(@PathVariable String type,@PathVariable String id,HttpServletRequest request) {
        long actor=actor();Map<String,String> query=AlertWorkflowController.query(request,"patientId","afterRevision","limit");
        long after=query.containsKey("afterRevision")?AlertWorkflowController.positive(query.get("afterRevision")):0;
        long limit=query.containsKey("limit")?AlertWorkflowController.positive(query.get("limit")):50;if(limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");
        return Result.ok(bridge.getObject().history(actor,AlertWorkflowController.positive(query.get("patientId")),choice(type,"sourceType",SOURCE_TYPES),AlertWorkflowController.positive(id),after,(int)limit));
    }
    @PostMapping("/{type}/{id}/invalidate") public Result<Map<String,Object>> invalidate(@PathVariable String type,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();AlertWorkflowController.query(request);fields(body,new HashSet<>(Arrays.asList("patientId","commandKey","expectedVersion","reason")));
        return Result.ok(bridge.getObject().correct(actor,choice(type,"sourceType",SOURCE_TYPES),AlertWorkflowController.positive(id),body,true,source->{throw new IllegalStateException("Invalidation cannot delete a native source.");}));
    }
    private long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null||actor<=0)throw AlertWorkflowException.denied();properties.requireEnabled();return actor;}
}
