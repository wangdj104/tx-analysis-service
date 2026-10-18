package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.commongoal.*;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** Authenticated actor comes only from the request identity, never the public command. */
@RestController
@CommonCareGoalExceptionAdvice.Api
@RequestMapping(value="/common-care-goals",produces="application/json")
public class CommonCareGoalController {
    private final CommonCareGoalService commands;
    private final CommonCareGoalQueryService queries;
    public CommonCareGoalController(CommonCareGoalService commands,CommonCareGoalQueryService queries){this.commands=commands;this.queries=queries;}
    @GetMapping("/capabilities") public ResponseEntity<Result<Map<String,Object>>> capabilities(HttpServletRequest request){
        Map<String,String> q=query(request,"patientId");return ok(queries.capabilities(actor(),positive(q.get("patientId"))));
    }
    @GetMapping public ResponseEntity<Result<Map<String,Object>>> list(HttpServletRequest request){
        Map<String,String> q=query(request,"patientId","state","cursor","limit");
        return ok(queries.list(actor(),positive(q.get("patientId")),q.get("state"),q.get("cursor"),page(q)));
    }
    @GetMapping("/{goal}/current") public ResponseEntity<Result<Map<String,Object>>> current(@PathVariable String goal,HttpServletRequest request){
        Map<String,String> q=query(request,"patientId");return ok(queries.current(actor(),positive(q.get("patientId")),positive(goal)));
    }
    @GetMapping("/{goal}/history") public ResponseEntity<Result<Map<String,Object>>> history(@PathVariable String goal,HttpServletRequest request){
        Map<String,String> q=query(request,"patientId","cursor","limit");return ok(queries.history(actor(),positive(q.get("patientId")),positive(goal),q.get("cursor"),page(q)));
    }
    @GetMapping("/{goal}/history/{sequence}") public ResponseEntity<Result<Map<String,Object>>> historyDetail(@PathVariable String goal,@PathVariable String sequence,HttpServletRequest request){
        Map<String,String> q=query(request,"patientId");return ok(queries.historyDetail(actor(),positive(q.get("patientId")),positive(goal),positive(sequence)));
    }
    @GetMapping("/candidates") public ResponseEntity<Result<Map<String,Object>>> candidates(HttpServletRequest request){
        Map<String,String> q=query(request,"patientId","name");return ok(queries.candidates(actor(),positive(q.get("patientId")),q.get("name")));
    }
    @GetMapping("/command-receipts/{key}") public ResponseEntity<Result<Map<String,Object>>> receipt(@PathVariable String key,HttpServletRequest request){
        Map<String,String> q=query(request,"patientId");return ok(queries.receipt(actor(),positive(q.get("patientId")),key));
    }
    @PostMapping(value="/commands",consumes="application/json") public ResponseEntity<Result<Map<String,Object>>> command(HttpServletRequest request)throws IOException{
        query(request);long actor=actor();return ok(commands.command(actor,body(request)));
    }
    private static String body(HttpServletRequest request)throws IOException{
        if(request.getContentLengthLong()>COMMAND_BYTES)throw limit();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n,total=0;
        while((n=request.getInputStream().read(buffer,0,Math.min(buffer.length,COMMAND_BYTES+1-total)))!=-1){
            total+=n;if(total>COMMAND_BYTES)throw limit();out.write(buffer,0,n);
        }
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}
        catch(CharacterCodingException failure){throw invalid();}
    }
    private static Map<String,String> query(HttpServletRequest request,String...allowed){
        Set<String> fields=new HashSet<>(Arrays.asList(allowed));Map<String,String> result=new LinkedHashMap<>();
        for(Map.Entry<String,String[]> e:request.getParameterMap().entrySet()){
            if(!fields.contains(e.getKey())||e.getValue()==null||e.getValue().length!=1)throw invalid();
            String value=e.getValue()[0];requireBytes(value,640);result.put(e.getKey(),value);
        }
        return result;
    }
    private static long positive(String value){
        if(value==null||!value.matches("[1-9][0-9]{0,15}"))throw invalid();
        try{return id(Long.parseLong(value));}catch(NumberFormatException failure){throw invalid();}
    }
    private static int page(Map<String,String> query){long value=query.containsKey("limit")?positive(query.get("limit")):DEFAULT_PAGE;if(value>MAX_PAGE)throw invalid();return(int)value;}
    private static long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null)throw FamilyAssistanceAuthorizationService.denied();return id(actor);}
    private static ResponseEntity<Result<Map<String,Object>>> ok(Map<String,Object> data){return ResponseEntity.ok().header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(Result.ok(data));}
}
