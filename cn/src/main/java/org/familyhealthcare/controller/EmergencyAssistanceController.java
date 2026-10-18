package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.emergencyassistance.*;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceContracts.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
/** 有限紧急请求命令；操作人只来自当前认证请求。 */
@RestController @EmergencyAssistanceExceptionAdvice.Api @RequestMapping("/emergency-assistance") public class EmergencyAssistanceController {
    private final EmergencyAssistanceService service;
    private final EmergencyAssistanceQueryService queries;
    private final EmergencyAssistanceNotificationWorker worker;
    public EmergencyAssistanceController(EmergencyAssistanceService service,EmergencyAssistanceQueryService queries,
    EmergencyAssistanceNotificationWorker worker) {
        this.service=service;
        this.queries=queries;
        this.worker=worker;
    }
    @GetMapping("/capabilities") public Result<Map<String,Object>> capabilities(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId");
        return Result.ok(queries.capabilities(actor(),positive(q.get("patientId"))));
    }
    @GetMapping public Result<Map<String,Object>> list(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId","cursor","limit");
        return Result.ok(queries.list(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));
    }
    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable String id,HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId");
        Map<String,Object> result=queries.detail(actor(),positive(id));
        if(q.containsKey("patientId")&&positive(q.get("patientId"))!=((Number)result.get("patientId")).longValue())throw EmergencyAssistanceAuthorizationService.denied();
        return Result.ok(result);
    }
    @GetMapping("/{id}/events") public Result<Map<String,Object>> events(@PathVariable String id,HttpServletRequest request) {
        Map<String,String> q=query(request,"cursor","limit");
        return Result.ok(queries.events(actor(),positive(id),q.get("cursor"),limit(q)));
    }
    @GetMapping("/{id}/delivery-status") public Result<Map<String,Object>> delivery(@PathVariable String id,
    HttpServletRequest request) {
        Map<String,String> q=query(request,"cursor","limit");
        return Result.ok(worker.deliveryStatus(actor(),positive(id),q.get("cursor"),limit(q)));
    }
    @GetMapping("/command-receipts/{key}") public Result<Map<String,Object>> receipt(@PathVariable String key,
    HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId");
        return Result.ok(queries.commandReceipt(actor(),positive(q.get("patientId")),key));
    }
    @PostMapping public Result<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        query(request);
        return Result.ok(service.command(actor(),null,EmergencyAssistanceContracts.parse("CREATE_REQUEST",
        body)));
    }
    @PostMapping("/{id}/{action}") public Result<Map<String,Object>> command(@PathVariable String id,
    @PathVariable String action,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        query(request);
        String operation=action.toUpperCase(Locale.ROOT).replace('-','_');
        if("CREATE_REQUEST".equals(operation)||!EmergencyAssistanceContracts.OPERATIONS.contains(operation)||!action.equals(operation.toLowerCase(Locale.ROOT).replace('_',
        '-')))throw WorkflowException.invalid("Unsupported assistance action.");
        return Result.ok(service.command(actor(),positive(id),EmergencyAssistanceContracts.parse(operation,
        body)));
    }
    @PostMapping("/notifications/{id}/retry") public Result<Map<String,Object>> retry(@PathVariable String id,
    @RequestBody Map<String,Object> body,HttpServletRequest request) {
        query(request);
        fields(body,"retryToken","duplicateRiskAcknowledged");
        String token=text(body.get("retryToken"),"retryToken",64);
        if(!token.matches("[0-9a-f]{64}")||!(body.get("duplicateRiskAcknowledged") instanceof Boolean))throw WorkflowException.invalid("Invalid delivery retry.");
        return Result.ok(worker.retry(actor(),positive(id),token,(Boolean)body.get("duplicateRiskAcknowledged")));
    }
    private static long actor() {
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(actor==null)throw EmergencyAssistanceAuthorizationService.denied();
        return org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.id(actor,
        "actor");
    }
    static Map<String,String> query(HttpServletRequest request,String...allowed) {
        Map<String,String> out=new LinkedHashMap<>();
        Set<String> names=new HashSet<>(Arrays.asList(allowed));
        for(Map.Entry<String,String[]> entry:request.getParameterMap().entrySet()) {
            if(!names.contains(entry.getKey())||entry.getValue().length!=1)throw WorkflowException.invalid("Unknown or repeated query field.");
            out.put(entry.getKey(),entry.getValue()[0]);
        }
        return out;
    }
    static long positive(String value) {
        if(value==null||!value.matches("[1-9][0-9]{0,15}"))throw WorkflowException.invalid("A safe positive integer ID is required.");
        try {
            return integer(Long.valueOf(value),"id",1,9007199254740991L);
        }
        catch(NumberFormatException e) {
            throw WorkflowException.invalid("ID is out of range.");
        }
    }
    static int limit(Map<String,String> q) {
        long limit=q.containsKey("limit")?positive(q.get("limit")):30;
        if(limit>100)throw WorkflowException.invalid("limit must be between 1 and 100.");
        return(int)limit;
    }
}
