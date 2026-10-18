package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.medicalrecord.*;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Supplier;

/** 只暴露有限 DTO；原始事实仍走既有显式授权接口。所有失败同样禁止缓存。 */
@RestController
@RequestMapping("/medical-record/corrections")
public class MedicalRecordCorrectionController {
    private final MedicalRecordCorrectionService commands;
    private final MedicalRecordCorrectionQueryService queries;
    public MedicalRecordCorrectionController(MedicalRecordCorrectionService commands,MedicalRecordCorrectionQueryService queries){this.commands=commands;this.queries=queries;}
    @PostMapping(consumes="application/json")
    public ResponseEntity<Result<?>> command(HttpServletRequest request){return response(()->{parameters(request);return commands.execute(body(request));});}
    @GetMapping("/{id}/current")
    public ResponseEntity<Result<?>> current(@PathVariable("id") String id,HttpServletRequest request){return response(()->{parameters(request,"patientId");return queries.current(id(id),patient(request));});}
    @GetMapping("/{id}/capabilities")
    public ResponseEntity<Result<?>> capabilities(@PathVariable("id") String id,HttpServletRequest request){return response(()->{parameters(request,"patientId");return queries.capabilities(id(id),patient(request));});}
    @GetMapping("/{id}/history")
    public ResponseEntity<Result<?>> history(@PathVariable("id") String id,HttpServletRequest request){return response(()->{parameters(request,"patientId","size","cursor");long size=request.getParameter("size")==null?20:id(request.getParameter("size"));if(size>50)throw MedicalRecordCorrectionContracts.invalid();return queries.history(id(id),patient(request),(int)size,request.getParameter("cursor"));});}
    @GetMapping("/{id}/history/{sequence}")
    public ResponseEntity<Result<?>> revision(@PathVariable("id") String id,@PathVariable("sequence") String sequence,HttpServletRequest request){return response(()->{parameters(request,"patientId");return queries.revision(id(id),patient(request),"0".equals(sequence)?0:id(sequence));});}
    private static void parameters(HttpServletRequest request,String...allowed){Set<String> keys=new HashSet<>(Arrays.asList(allowed));for(Map.Entry<String,String[]> entry:request.getParameterMap().entrySet())if(!keys.contains(entry.getKey())||entry.getValue()==null||entry.getValue().length!=1)throw MedicalRecordCorrectionContracts.invalid();}
    private static Long patient(HttpServletRequest request){String value=request.getParameter("patientId");return value==null||"null".equals(value)?null:id(value);}
    private static long id(String value){if(value==null||value.length()>16||!value.matches("[1-9][0-9]*"))throw MedicalRecordCorrectionContracts.invalid();try{return MedicalRecordCorrectionContracts.id(Long.parseLong(value));}catch(NumberFormatException failure){throw MedicalRecordCorrectionContracts.invalid();}}
    private static String body(HttpServletRequest request){
        if(request.getContentLengthLong()>MedicalRecordCorrectionContracts.COMMAND_BYTES)throw MedicalRecordCorrectionContracts.limit();
        try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream input=request.getInputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer,0,Math.min(buffer.length,MedicalRecordCorrectionContracts.COMMAND_BYTES+1-bytes.size())))!=-1){bytes.write(buffer,0,n);if(bytes.size()>MedicalRecordCorrectionContracts.COMMAND_BYTES)throw MedicalRecordCorrectionContracts.limit();}
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }catch(IOException failure){throw MedicalRecordCorrectionContracts.invalid();}
    }
    private static ResponseEntity<Result<?>> response(Supplier<Map<String,Object>> action){
        try{return headers(200,Result.ok(action.get()));}
        catch(WorkflowException failure){return failure(failure);}
        catch(RuntimeException failure){return failure(new WorkflowException(503,"MEDICAL_RECORD_UNAVAILABLE","暂时无法完成病历操作。若结果未知，请重试同一个请求。"));}
    }
    public static ResponseEntity<Result<?>> failure(WorkflowException failure){String message=failure.getMessage();if(Arrays.asList("AUTHORITY_CHANGED","ADMISSION_CONTEXT_CHANGED").contains(failure.getErrorCode()))message="当前权限已改变，请刷新后重试。";if("INVALID_REQUEST".equals(failure.getErrorCode()))message="病历请求不符合要求。";Result<Map<String,Object>> result=Result.error(failure.getStatus(),message);result.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));return headers(failure.getStatus(),result);}
    private static ResponseEntity<Result<?>> headers(int status,Result<?> result){return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);}
}
