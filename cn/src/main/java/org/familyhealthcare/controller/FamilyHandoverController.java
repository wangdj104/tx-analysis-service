package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.familyassistance.FamilyHandoverProjection;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.service.familyassistance.FamilyHandoverQueryService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 仅新增只读普通交接入口；patient 和来源仍由服务端重新核验，不能用链接取得授权。 */
@RestController
@FamilyAssistanceExceptionAdvice.Api
@RequestMapping(value="/family-assistance",produces="application/json")
public class FamilyHandoverController {
    private final FamilyHandoverQueryService queries;
    public FamilyHandoverController(FamilyHandoverQueryService queries){this.queries=queries;}
    @GetMapping("/handover-sources/{careItemId}")
    public ResponseEntity<Result<Map<String,Object>>> preview(@PathVariable String careItemId,HttpServletRequest request) {
        return ok(queries.preview(actor(),patient(request),positive(careItemId)));
    }
    @GetMapping("/{id}/handover-content")
    public ResponseEntity<Result<Map<String,Object>>> content(@PathVariable String id,HttpServletRequest request) {
        return ok(queries.content(actor(),patient(request),positive(id)));
    }
    @GetMapping("/handover-work")
    public ResponseEntity<Result<Map<String,Object>>> work(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId","cursor","limit");
        long limit=q.containsKey("limit")?positive(q.get("limit")):25;
        if(limit>25)throw FamilyHandoverProjection.invalidRequest();
        return ok(queries.work(actor(),positive(q.get("patientId")),q.get("cursor"),(int)limit));
    }
    @GetMapping("/handover-candidates")
    public ResponseEntity<Result<Map<String,Object>>> candidates(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId","operation","careItemId","assistanceId","search");
        return ok(queries.candidates(actor(),positive(q.get("patientId")),q.get("operation"),
                q.containsKey("careItemId")?positive(q.get("careItemId")):null,
                q.containsKey("assistanceId")?positive(q.get("assistanceId")):null,q.get("search")));
    }
    private static Map<String,String> query(HttpServletRequest request,String...allowed){
        try{return FamilyAssistanceController.query(request,allowed);}
        catch(WorkflowException failure){throw FamilyHandoverProjection.invalidRequest();}
    }
    private static long patient(HttpServletRequest request){
        try{return positive(FamilyAssistanceController.query(request,"patientId").get("patientId"));}
        catch(WorkflowException failure){throw FamilyHandoverProjection.invalidRequest();}
    }
    private static long positive(String value){
        try{return FamilyAssistanceController.positive(value);}
        catch(WorkflowException failure){throw FamilyHandoverProjection.invalidRequest();}
    }
    private static long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null)throw FamilyHandoverProjection.accessDenied();return actor;}
    private static ResponseEntity<Result<Map<String,Object>>> ok(Map<String,Object> data){
        return ResponseEntity.ok().header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(Result.ok(data));
    }
}
