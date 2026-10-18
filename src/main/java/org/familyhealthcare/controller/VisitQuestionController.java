package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.visitquestion.*;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** 身份只取认证请求；在解析 JSON 之前限制实际输入字节，所有结果禁止共享缓存。 */
@RestController
@RequestMapping("/care/questions")
public class VisitQuestionController {
    private final VisitQuestionQueryService queries;
    private final VisitQuestionContextService contexts;
    private final VisitQuestionService commands;
    public VisitQuestionController(VisitQuestionQueryService queries,VisitQuestionContextService contexts,VisitQuestionService commands){this.queries=queries;this.contexts=contexts;this.commands=commands;}
    @ModelAttribute public void privateResponse(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");response.setHeader("X-Content-Type-Options","nosniff");}
    @GetMapping("/capabilities") public Result<Map<String,Object>> capabilities(HttpServletRequest request){Map<String,String> q=query(request,"patientId");return Result.ok(queries.capabilities(actor(),positive(q.get("patientId"))));}
    @GetMapping public Result<Map<String,Object>> page(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit","state","contextType","contextId");return Result.ok(queries.list(actor(),positive(q.get("patientId")),false,q.get("state"),q.get("contextType"),optional(q.get("contextId")),q.get("cursor"),limit(q)));}
    @GetMapping("/legacy") public Result<Map<String,Object>> legacy(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return Result.ok(queries.list(actor(),positive(q.get("patientId")),true,null,null,null,q.get("cursor"),limit(q)));}
    @GetMapping("/legacy/{id}") public Result<Map<String,Object>> legacyDetail(@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"patientId");return Result.ok(queries.legacyDetail(actor(),positive(q.get("patientId")),positive(id)));}
    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"patientId");return Result.ok(queries.detail(actor(),positive(q.get("patientId")),positive(id)));}
    @GetMapping("/{id}/events") public Result<Map<String,Object>> events(@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return Result.ok(queries.events(actor(),positive(q.get("patientId")),positive(id),q.get("cursor"),limit(q)));}
    @GetMapping("/{id}/events/{event}") public Result<Map<String,Object>> event(@PathVariable String id,@PathVariable String event,HttpServletRequest request){Map<String,String> q=query(request,"patientId");return Result.ok(queries.eventDetail(actor(),positive(q.get("patientId")),positive(id),positive(event)));}
    @GetMapping("/contexts") public Result<Map<String,Object>> contexts(HttpServletRequest request){Map<String,String> q=query(request,"patientId","type","cursor","limit");return Result.ok(contexts.options(actor(),positive(q.get("patientId")),required(q,"type"),q.get("cursor"),limit(q)));}
    @GetMapping("/contexts/{type}/{id}") public Result<Map<String,Object>> context(@PathVariable String type,@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"patientId");return Result.ok(contexts.exact(actor(),positive(q.get("patientId")),type,positive(id)));}
    @GetMapping("/command-receipts/{key}") public Result<Map<String,Object>> receipt(@PathVariable String key,HttpServletRequest request){Map<String,String> q=query(request,"patientId");return Result.ok(queries.receipt(actor(),positive(q.get("patientId")),key));}
    @PostMapping public Result<Map<String,Object>> create(HttpServletRequest request)throws IOException{query(request);return Result.ok(commands.command(actor(),null,key(request),body(request)));}
    @PostMapping("/{id}/commands") public Result<Map<String,Object>> command(@PathVariable String id,HttpServletRequest request)throws IOException{query(request);return Result.ok(commands.command(actor(),positive(id),key(request),body(request)));}
    private static String key(HttpServletRequest request){Enumeration<String> keys=request.getHeaders("Idempotency-Key");if(keys==null||!keys.hasMoreElements())throw WorkflowException.invalid("Idempotency-Key is required.");String key=keys.nextElement();if(keys.hasMoreElements())throw WorkflowException.invalid("Repeated command key.");return org.familyhealthcare.service.workflow.WorkflowCommandExecutor.validateKey(key);}
    private static String body(HttpServletRequest request)throws IOException{
        if(request.getContentLengthLong()>VisitQuestionContracts.ORDINARY_BYTES)throw limitError();
        ByteArrayOutputStream out=new ByteArrayOutputStream(VisitQuestionContracts.ORDINARY_BYTES);byte[] buffer=new byte[4096];int n;
        while((n=request.getInputStream().read(buffer))!=-1){if(out.size()+n>VisitQuestionContracts.ORDINARY_BYTES)throw limitError();out.write(buffer,0,n);}
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(CharacterCodingException invalid){throw WorkflowException.invalid("Question JSON must be valid UTF-8.");}
    }
    private static WorkflowException limitError(){return new WorkflowException(413,"QUESTION_LIMIT","Question command is too large.");}
    private static long actor(){Long value=CurrentUserUtil.getCurrentUserId();if(value==null)throw org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.denied();return VisitQuestionContracts.id(value,"actor");}
    private static String required(Map<String,String> q,String key){String value=q.get(key);if(value==null)throw WorkflowException.invalid("Missing "+key+".");return value;}
    private static Long optional(String value){return value==null?null:positive(value);}
    private static long positive(String value){if(value==null||!value.matches("[1-9][0-9]{0,15}"))throw WorkflowException.invalid("A safe positive ID is required.");return VisitQuestionContracts.id(Long.valueOf(value),"id");}
    private static Integer limit(Map<String,String> q){if(!q.containsKey("limit"))return 25;long value=positive(q.get("limit"));if(value>50)throw WorkflowException.invalid("Question page size must be between 1 and 50.");return (int)value;}
    private static Map<String,String> query(HttpServletRequest request,String...allowed){Set<String> names=new HashSet<>(Arrays.asList(allowed));Map<String,String> out=new LinkedHashMap<>();for(Map.Entry<String,String[]> entry:request.getParameterMap().entrySet()){if(!names.contains(entry.getKey())||entry.getValue().length!=1||entry.getValue()[0].length()>256)throw WorkflowException.invalid("Unknown, repeated or oversized question query field.");out.put(entry.getKey(),entry.getValue()[0]);}return out;}
    @ExceptionHandler(WorkflowException.class) public ResponseEntity<Result<Map<String,Object>>> workflow(WorkflowException failure){return error(failure.getStatus(),failure.getErrorCode(),failure.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,IOException.class}) public ResponseEntity<Result<Map<String,Object>>> invalid(Exception failure){return error(400,"INVALID_REQUEST","Invalid question request.");}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<Result<Map<String,Object>>> storage(Exception failure){return error(503,"QUESTION_INCOMPATIBLE","Question storage could not be verified. No completed command is confirmed.");}
    private static ResponseEntity<Result<Map<String,Object>>> error(int status,String code,String message){Result<Map<String,Object>> result=Result.error(status,message);result.setData(Collections.singletonMap("errorCode",code));return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);}
}
