package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.medicationexecution.*;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
/** Finite typed medication commands; actor comes only from the authenticated request. */
@RestController
@MedicationExecutionExceptionAdvice.Api
@RequestMapping("/medication-executions")
public class MedicationExecutionController {
    @org.springframework.beans.factory.annotation.Autowired private MedicationBoundaryService boundary;
    private final MedicationExecutionService service;
    @org.springframework.beans.factory.annotation.Autowired private MedicationIntakeService intakes;
    private final MedicationExecutionQueryService queries;
    private final MedicationExecutionNotificationWorker worker;
    public MedicationExecutionController(MedicationExecutionService service,MedicationExecutionQueryService queries,MedicationExecutionNotificationWorker worker) {
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
    @GetMapping("/medications") public Result<Map<String,Object>> medications(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId","cursor","limit");
        return Result.ok(queries.medications(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));
    }
    @GetMapping("/medications/{id}") public Result<Map<String,Object>> medication(@PathVariable String id,HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId");
        return Result.ok(queries.medication(actor(),positive(q.get("patientId")),positive(id)));
    }
    @GetMapping("/clinicians") public Result<Map<String,Object>> clinicians(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId","cursor","limit");
        return Result.ok(queries.clinicians(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));
    }
    @GetMapping("/sources") public Result<Map<String,Object>> sources(HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId","cursor","limit");
        return Result.ok(queries.sources(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));
    }
    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable String id,HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId");
        Map<String,Object> result=queries.detail(actor(),positive(id));
        if(q.containsKey("patientId")&&positive(q.get("patientId"))!=((Number)result.get("patientId")).longValue())throw MedicationExecutionAuthorizationService.denied();
        return Result.ok(result);
    }
    @GetMapping("/{id}/replacement-preview") public Result<Map<String,Object>> replacementPreview(@PathVariable String id,HttpServletRequest request) { query(request);return Result.ok(boundary.preview(actor(),positive(id))); }
    @GetMapping("/{id}/intakes") public Result<Map<String,Object>> intakes(@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"cursor","limit");return Result.ok(queries.intakes(actor(),positive(id),q.get("cursor"),limit(q)));}
    @GetMapping("/personal-intakes") public Result<Map<String,Object>> personalIntakes(HttpServletRequest request){Map<String,String> q=query(request,"patientId","medicationId","cursor","limit");return Result.ok(queries.personalIntakes(actor(),positive(q.get("patientId")),positive(q.get("medicationId")),q.get("cursor"),limit(q)));}
    @PostMapping("/personal-stock-fact") public Result<Map<String,Object>> personalFact(@RequestBody Map<String,Object> body,HttpServletRequest request){query(request);return Result.ok(intakes.recordPersonalStockFact(actor(),parsePersonalStockFact(body)));}
    @GetMapping("/{id}/events") public Result<Map<String,Object>> events(@PathVariable String id,HttpServletRequest request) {
        Map<String,String> q=query(request,"cursor","limit");
        return Result.ok(queries.events(actor(),positive(id),q.get("cursor"),limit(q)));
    }
    @GetMapping("/{id}/delivery-status") public Result<Map<String,Object>> delivery(@PathVariable String id,HttpServletRequest request) {
        Map<String,String> q=query(request,"cursor","limit");
        return Result.ok(worker.deliveryStatus(actor(),positive(id),q.get("cursor"),limit(q)));
    }
    @GetMapping("/command-receipts/{key}") public Result<Map<String,Object>> receipt(@PathVariable String key,HttpServletRequest request) {
        Map<String,String> q=query(request,"patientId");
        return Result.ok(queries.commandReceipt(actor(),positive(q.get("patientId")),key));
    }
    @PostMapping("/create-reconciliation") public Result<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        query(request);
        return Result.ok(service.command(actor(),null,parse("CREATE_RECONCILIATION",body)));
    }
    @PostMapping("/{id}/{action}") public Result<Map<String,Object>> command(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        query(request);
        String operation=action.toUpperCase(Locale.ROOT).replace('-','_');
        if(BOUNDARY_OPERATIONS.contains(operation)&&action.equals(operation.toLowerCase(Locale.ROOT).replace('_','-')))return Result.ok(boundary.command(actor(),positive(id),parseBoundary(operation,body)));
        if(INTAKE_OPERATIONS.contains(operation)&&action.equals(operation.toLowerCase(Locale.ROOT).replace('_','-')))return Result.ok(intakes.command(actor(),positive(id),parseIntake(operation,body)));
        if(!OPERATIONS.contains(operation)||"CREATE_RECONCILIATION".equals(operation)||!action.equals(operation.toLowerCase(Locale.ROOT).replace('_','-')))throw WorkflowException.invalid("Unsupported medication action.");
        return Result.ok(service.command(actor(),positive(id),parse(operation,body)));
    }
    @PostMapping("/notifications/{id}/retry") public Result<Map<String,Object>> retry(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        query(request);
        fields(body,"retryToken","duplicateRiskAcknowledged");
        String token=text(body.get("retryToken"),"retryToken",64);
        if(!token.matches("[0-9a-f]{64}")||!(body.get("duplicateRiskAcknowledged") instanceof Boolean))throw WorkflowException.invalid("Invalid delivery retry.");
        return Result.ok(worker.retry(actor(),positive(id),token,(Boolean)body.get("duplicateRiskAcknowledged")));
    }
    private static long actor() {
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(actor==null)throw MedicationExecutionAuthorizationService.denied();
        return MedicationExecutionContracts.id(actor,"actor");
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
