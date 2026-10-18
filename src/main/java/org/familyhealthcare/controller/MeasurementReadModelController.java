package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/** 新读取边界保留真实503；不能沿用旧工作流的503转404规则。 */
@RestController
@RequestMapping("/alert-workflows/read-model")
public class MeasurementReadModelController {
    private final MeasurementTrendQueryService trends;
    private final MeasurementItemQueryService items;
    public MeasurementReadModelController(MeasurementTrendQueryService trends,MeasurementItemQueryService items){this.trends=trends;this.items=items;}
    @GetMapping("/measurement-trend")
    public ResponseEntity<Result<Map<String,Object>>> trend(HttpServletRequest request){
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(actor==null||actor<=0)throw new AlertWorkflowException(401,"AUTHENTICATION_REQUIRED","Sign in to view measurements.");
        Map<String,String> query=AlertWorkflowController.query(request,"patientId","sourceType","metric","from","to","cursor","limit");
        long limit=query.containsKey("limit")?AlertWorkflowController.positive(query.get("limit")):50;
        if(limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");
        return response(200,Result.ok(trends.page(actor,AlertWorkflowController.positive(query.get("patientId")),query.get("sourceType"),query.get("metric"),query.get("from"),query.get("to"),query.get("cursor"),(int)limit)));
    }
    @GetMapping("/measurement-items")
    public ResponseEntity<Result<Map<String,Object>>> items(HttpServletRequest request){
        long actor=actor();Map<String,String> query=AlertWorkflowController.query(request,"patientId","cursor","limit");
        return response(200,Result.ok(items.page(actor,AlertWorkflowController.positive(query.get("patientId")),query.get("cursor"),itemLimit(query))));
    }
    @GetMapping("/measurement-items/{alertId}/events")
    public ResponseEntity<Result<Map<String,Object>>> events(@PathVariable("alertId") String alertId,HttpServletRequest request){
        long actor=actor();Map<String,String> query=AlertWorkflowController.query(request,"patientId","cursor","limit");
        return response(200,Result.ok(items.events(actor,AlertWorkflowController.positive(query.get("patientId")),AlertWorkflowController.positive(alertId),query.get("cursor"),itemLimit(query))));
    }
    private long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null||actor<=0)throw new AlertWorkflowException(401,"AUTHENTICATION_REQUIRED","Sign in to view measurements.");return actor;}
    private int itemLimit(Map<String,String> query){long limit=query.containsKey("limit")?AlertWorkflowController.positive(query.get("limit")):30;if(limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");return(int)limit;}
    @ExceptionHandler(AlertWorkflowException.class)
    public ResponseEntity<Result<Map<String,Object>>> workflow(AlertWorkflowException failure){
        Result<Map<String,Object>> result=Result.error(failure.getStatus(),failure.getMessage());
        result.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));return response(failure.getStatus(),result);
    }
    private ResponseEntity<Result<Map<String,Object>>> response(int status,Result<Map<String,Object>> result){
        return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);
    }
}
