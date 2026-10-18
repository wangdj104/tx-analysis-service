package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.MonitoringService;
import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.familyhealthcare.service.measurementread.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/** 新路径单独处理真实错误；不改变旧列表、报告或默认概览的响应合同。 */
@RestController
public class OriginalMeasurementReadController {
    private final OriginalMeasurementReadService originals;
    private final MonitoringService monitoring;
    public OriginalMeasurementReadController(OriginalMeasurementReadService originals,MonitoringService monitoring){this.originals=originals;this.monitoring=monitoring;}
    @GetMapping("/bp-self-monitor/page") public ResponseEntity<Result<?>> self(HttpServletRequest request){return page(request,true);}
    @GetMapping("/care-journey/measurements/page") public ResponseEntity<Result<?>> journey(HttpServletRequest request){return page(request,false);}
    private ResponseEntity<Result<?>> page(HttpServletRequest request,boolean self){
        long actor=OriginalMeasurementReadAuthorization.actor();Map<String,String>q=query(request,"patientId",self?"measureType":"metricType","from","to","cursor","limit");
        long patient=positive(q.get("patientId"));int limit=size(q,"limit",30,100);
        return response(200,Result.ok(self?originals.selfPage(actor,patient,q.get("measureType"),q.get("from"),q.get("to"),q.get("cursor"),limit):originals.journeyPage(actor,patient,q.get("metricType"),q.get("from"),q.get("to"),q.get("cursor"),limit)));
    }
    @GetMapping("/bp-self-monitor/detail") public ResponseEntity<Result<?>> detail(HttpServletRequest request) {
        long actor = OriginalMeasurementReadAuthorization.actor();
        if (!request.getParameterMap().containsKey("includeImportFacts")) {
            Map<String,String> q = query(request, "patientId", "recordId");
            return response(200, Result.ok(originals.selfDetail(actor, positive(q.get("patientId")), positive(q.get("recordId")))));
        }
        Map<String,String> q;
        try {
            q = query(request, "patientId", "recordId", "includeImportFacts");
            if (!"true".equals(q.get("includeImportFacts"))) throw OriginalMeasurementReadAuthorization.invalid();
            for (String key : Arrays.asList("patientId", "recordId"))
                if (q.get(key) == null || q.get(key).length() > 19) throw OriginalMeasurementReadAuthorization.invalid();
            positive(q.get("patientId")); positive(q.get("recordId"));
        } catch (AlertWorkflowException invalid) {
            throw new AlertWorkflowException(400, "INVALID_IMPORT_FACTS_SELECTOR", org.familyhealthcare.util.ExportLocalization.text(
                    "The original-facts query is invalid.", "原始事实查询参数无效。"));
        }
        return response(200, Result.ok(originals.selfDetail(actor, positive(q.get("patientId")), positive(q.get("recordId")), true)));
    }
    @ExceptionHandler(org.familyhealthcare.service.ClinicalImportFacts.Failure.class)
    public ResponseEntity<Result<?>> importFactsFailure(org.familyhealthcare.service.ClinicalImportFacts.Failure failure) {
        Result<Map<String,Object>> result = Result.error(failure.getStatus(), failure.getMessage());
        result.setData(Collections.singletonMap("errorCode", failure.getCode()));
        return response(failure.getStatus(), result);
    }
    @GetMapping("/monitoring/snapshot-bounded") public ResponseEntity<Result<?>> monitoring(HttpServletRequest request){
        OriginalMeasurementReadAuthorization.actor();Map<String,String>q=query(request,"patientId","days");return response(200,Result.ok(monitoring.getBoundedSnapshot(positive(q.get("patientId")),size(q,"days",7,90))));
    }
    /** 只为这四个新路径拒绝其它方法，避免旧全局advice把405变成HTTP 200。 */
    @RequestMapping({"/bp-self-monitor/page","/care-journey/measurements/page","/bp-self-monitor/detail","/monitoring/snapshot-bounded"})
    public ResponseEntity<Result<?>> methodNotAllowed(){return failure(new AlertWorkflowException(405,"METHOD_NOT_ALLOWED","请使用GET读取测量原记录。"));}
    private static Map<String,String> query(HttpServletRequest request,String...fields){try{return AlertWorkflowController.query(request,fields);}catch(AlertWorkflowException invalid){throw OriginalMeasurementReadAuthorization.invalid();}}
    private static long positive(String raw){try{return AlertWorkflowController.positive(raw);}catch(AlertWorkflowException invalid){throw OriginalMeasurementReadAuthorization.invalid();}}
    private static int size(Map<String,String>q,String key,int fallback,int max){long v=q.containsKey(key)?positive(q.get(key)):fallback;if(v>max)throw OriginalMeasurementReadAuthorization.invalid();return(int)v;}
    @ExceptionHandler(AlertWorkflowException.class) public ResponseEntity<Result<?>> failure(AlertWorkflowException failure){Result<Map<String,Object>>result=Result.error(failure.getStatus(),failure.getMessage());result.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));return response(failure.getStatus(),result);}
    private static ResponseEntity<Result<?>> response(int status,Result<?>body){
        ResponseEntity.BodyBuilder response=ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff");
        if(status==405)response.header("Allow","GET, HEAD");return response.body(body);
    }
}
