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
        if(actor==null||actor<=0)throw new AlertWorkflowException(401,"AUTHENTICATION_REQUIRED","请先登录后查看测量记录。");
        Map<String,String> query=AlertWorkflowController.query(request,"patientId","sourceType","metric","from","to","cursor","limit");
        long limit=query.containsKey("limit")?AlertWorkflowController.positive(query.get("limit")):50;
        if(limit>100)throw AlertWorkflowException.invalid("每页数量必须在1至100之间。");
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
    private long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null||actor<=0)throw new AlertWorkflowException(401,"AUTHENTICATION_REQUIRED","请先登录后查看测量记录。");return actor;}
    private int itemLimit(Map<String,String> query){long limit=query.containsKey("limit")?AlertWorkflowController.positive(query.get("limit")):30;if(limit>100)throw AlertWorkflowException.invalid("每页数量必须在1至100之间。");return(int)limit;}
    @ExceptionHandler(AlertWorkflowException.class)
    public ResponseEntity<Result<Map<String,Object>>> workflow(AlertWorkflowException failure){
        Result<Map<String,Object>> result=Result.error(failure.getStatus(),message(failure));
        result.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));return response(failure.getStatus(),result);
    }
    private String message(AlertWorkflowException failure){
        if("FEATURE_DISABLED".equals(failure.getErrorCode()))return "测量随访功能尚未启用。";
        if("ACCESS_DENIED".equals(failure.getErrorCode()))return "您没有查看此患者测量记录的权限。";
        String message=failure.getMessage();
        if("Unsupported or repeated query field.".equals(message))return "查询中包含不支持或重复的参数。";
        if("A positive integer ID is required.".equals(message))return "患者、事项编号及每页数量必须是正整数。";
        if("Integer ID is out of range.".equals(message))return "整数参数超出允许范围。";
        return message;
    }
    private ResponseEntity<Result<Map<String,Object>>> response(int status,Result<Map<String,Object>> result){
        return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);
    }
}
