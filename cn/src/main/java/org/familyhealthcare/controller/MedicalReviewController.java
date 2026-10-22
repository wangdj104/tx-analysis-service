package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.medicalreview.MedicalReviewApiContracts;
import org.familyhealthcare.service.medicalreview.MedicalReviewCommandService;
import org.familyhealthcare.service.medicalreview.MedicalReviewContracts;
import org.familyhealthcare.service.medicalreview.MedicalReviewQueryService;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Supplier;

/** Finite raw HTTP boundary. Service proxy completion precedes successful response construction. */
@RestController
@RequestMapping(value="/medical-record/reviews", method={RequestMethod.GET,RequestMethod.HEAD,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE,RequestMethod.OPTIONS,RequestMethod.TRACE})
@ConditionalOnProperty(name="medical-review.api-enabled", havingValue="true", matchIfMissing=false)
public class MedicalReviewController {
    private final MedicalReviewCommandService commands;
    private final MedicalReviewQueryService queries;
    public MedicalReviewController(MedicalReviewCommandService commands, MedicalReviewQueryService queries) {
        this.commands=commands; this.queries=queries;
    }

    @RequestMapping("/{recordId}/current")
    public ResponseEntity<Result<?>> current(@PathVariable("recordId") String recordId, HttpServletRequest request) {
        return response(request,"GET",()->{
            Map<String,String> p=parameters(request,"patientId"); noBody(request);
            return queries.current(MedicalReviewApiContracts.id(recordId),patient(p));
        });
    }

    @RequestMapping("/{recordId}/{channel}/history")
    public ResponseEntity<Result<?>> history(@PathVariable("recordId") String recordId,
            @PathVariable("channel") String channel, HttpServletRequest request) {
        return response(request,"GET",()->{
            Map<String,String> p=parameters(request,"patientId","size","cursor"); noBody(request);
            MedicalReviewContracts.Channel selected=MedicalReviewApiContracts.channel(channel); Long patient=patient(p);
            if(selected==MedicalReviewContracts.Channel.DOCTOR && patient==null)throw MedicalReviewApiContracts.invalidRequest();
            String cursor=p.get("cursor"); if(cursor!=null)MedicalReviewApiContracts.cursor(cursor);
            return queries.history(MedicalReviewApiContracts.id(recordId),patient,selected,MedicalReviewApiContracts.size(p.get("size")),cursor);
        });
    }

    @RequestMapping("/{recordId}/{channel}/history/{decisionVersion}")
    public ResponseEntity<Result<?>> detail(@PathVariable("recordId") String recordId,
            @PathVariable("channel") String channel, @PathVariable("decisionVersion") String decisionVersion, HttpServletRequest request) {
        return response(request,"GET",()->{
            Map<String,String> p=parameters(request,"patientId","cursor"); noBody(request);
            MedicalReviewContracts.Channel selected=MedicalReviewApiContracts.channel(channel); Long patient=patient(p);
            if(selected==MedicalReviewContracts.Channel.DOCTOR && patient==null)throw MedicalReviewApiContracts.invalidRequest();
            String cursor=p.get("cursor"); if(cursor==null)throw MedicalReviewApiContracts.invalidRequest(); MedicalReviewApiContracts.cursor(cursor);
            return queries.detail(MedicalReviewApiContracts.id(recordId),patient,selected,MedicalReviewApiContracts.id(decisionVersion),cursor);
        });
    }

    @RequestMapping("/{recordId}/{channel}/commands")
    public ResponseEntity<Result<?>> command(@PathVariable("recordId") String recordId,
            @PathVariable("channel") String channel, HttpServletRequest request) {
        return response(request,"POST",()->{
            parameters(request); media(request);
            return commands.execute(MedicalReviewApiContracts.id(recordId),MedicalReviewApiContracts.channel(channel),body(request));
        });
    }

    @RequestMapping("/{recordId}/{channel}/receipt")
    public ResponseEntity<Result<?>> receipt(@PathVariable("recordId") String recordId,
            @PathVariable("channel") String channel, HttpServletRequest request) {
        return response(request,"POST",()->{
            parameters(request); media(request);
            return commands.reconcile(MedicalReviewApiContracts.id(recordId),MedicalReviewApiContracts.channel(channel),body(request));
        });
    }

    @RequestMapping({"", "/", "/**"})
    public ResponseEntity<Result<?>> missing(HttpServletRequest request) {
        return failure(MedicalReviewApiContracts.error(404,"MEDICAL_REVIEW_ROUTE_NOT_FOUND"),null);
    }

    private static Long patient(Map<String,String> p) {
        if(!p.containsKey("patientId"))throw MedicalReviewApiContracts.invalidRequest();
        return MedicalReviewApiContracts.nullableId(p.get("patientId"));
    }

    private static Map<String,String> parameters(HttpServletRequest request, String... allowed) {
        Set<String> keys=new HashSet<>(Arrays.asList(allowed)); Map<String,String> result=new LinkedHashMap<>();
        String raw=request.getQueryString();
        if(raw!=null && !raw.isEmpty()) {
            for(String part:raw.split("&",-1)) {
                int at=part.indexOf('='); if(at<0)throw MedicalReviewApiContracts.invalidRequest();
                String key=decode(part.substring(0,at)), value=decode(part.substring(at+1));
                if(!keys.contains(key) || result.putIfAbsent(key,value)!=null)throw MedicalReviewApiContracts.invalidRequest();
            }
        } else {
            for(Map.Entry<String,String[]> entry:request.getParameterMap().entrySet()) {
                String[] value=entry.getValue();
                if(!keys.contains(entry.getKey()) || value==null || value.length!=1 || value[0]==null)throw MedicalReviewApiContracts.invalidRequest();
                result.put(entry.getKey(),value[0]);
            }
        }
        return result;
    }

    private static String decode(String value) {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i); if(c>127)throw MedicalReviewApiContracts.invalidRequest();
            if(c=='%') {
                if(i+2>=value.length())throw MedicalReviewApiContracts.invalidRequest();
                char high=value.charAt(++i), low=value.charAt(++i);
                if(high>127 || low>127)throw MedicalReviewApiContracts.invalidRequest();
                int a=Character.digit(high,16), b=Character.digit(low,16);
                if(a<0 || b<0)throw MedicalReviewApiContracts.invalidRequest(); bytes.write(a*16+b);
            } else bytes.write(c=='+'?' ':c);
        }
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch(CharacterCodingException failure) { throw MedicalReviewApiContracts.invalidRequest(); }
    }

    private static String oneHeader(HttpServletRequest request, String name) {
        Enumeration<String> values=request.getHeaders(name); if(values==null || !values.hasMoreElements())return null;
        String value=values.nextElement(); if(values.hasMoreElements())throw MedicalReviewApiContracts.error(415,"MEDICAL_REVIEW_UNSUPPORTED_MEDIA_TYPE"); return value;
    }

    private static void media(HttpServletRequest request) {
        String type=oneHeader(request,"Content-Type"), encoding=oneHeader(request,"Content-Encoding");
        if(type==null || !type.matches("(?i)\\s*application/json\\s*(?:;\\s*charset\\s*=\\s*(?:UTF-8|\"UTF-8\")\\s*)?")
                || (encoding!=null && !"identity".equalsIgnoreCase(encoding.trim())))
            throw MedicalReviewApiContracts.error(415,"MEDICAL_REVIEW_UNSUPPORTED_MEDIA_TYPE");
    }

    private static void noBody(HttpServletRequest request) {
        if(request.getContentLengthLong()>0)throw MedicalReviewApiContracts.invalidRequest();
        try { if(request.getInputStream().read()!=-1)throw MedicalReviewApiContracts.invalidRequest(); }
        catch(IOException failure) { throw MedicalReviewApiContracts.invalidRequest(); }
    }

    private static byte[] body(HttpServletRequest request) {
        Enumeration<String> declaredValues=request.getHeaders("Content-Length");
        if(declaredValues!=null && declaredValues.hasMoreElements()) {
            String value=declaredValues.nextElement();
            if(declaredValues.hasMoreElements() || value==null || !value.matches("[0-9]+"))throw MedicalReviewApiContracts.invalidRequest();
            String canonical=value.replaceFirst("^0+(?!$)","");
            if(canonical.length()>5 || Long.parseLong(canonical)>MedicalReviewContracts.COMMAND_BYTES)
                throw MedicalReviewApiContracts.error(413,"MEDICAL_REVIEW_LIMIT");
        }
        long declared=request.getContentLengthLong();
        if(declared>MedicalReviewContracts.COMMAND_BYTES)throw MedicalReviewApiContracts.error(413,"MEDICAL_REVIEW_LIMIT");
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); InputStream input=request.getInputStream(); byte[] buffer=new byte[4096];
            while(true) {
                int n=input.read(buffer,0,Math.min(buffer.length,MedicalReviewContracts.COMMAND_BYTES+1-bytes.size()));
                if(n<0)break;
                if(n==0){int value=input.read();if(value<0)break;bytes.write(value);}else bytes.write(buffer,0,n);
                if(bytes.size()>MedicalReviewContracts.COMMAND_BYTES)throw MedicalReviewApiContracts.error(413,"MEDICAL_REVIEW_LIMIT");
            }
            return bytes.toByteArray();
        } catch(IOException failure) { throw MedicalReviewApiContracts.invalidRequest(); }
    }

    private static ResponseEntity<Result<?>> response(HttpServletRequest request, String method, Supplier<Map<String,Object>> action) {
        try {
            if(!method.equals(request.getMethod()))return failure(MedicalReviewApiContracts.error(405,"MEDICAL_REVIEW_METHOD_NOT_ALLOWED"),method);
            return headers(200,Result.ok(action.get()),null);
        } catch(WorkflowException failure) { return failure(failure,null); }
        catch(org.familyhealthcare.service.ClinicalImportFacts.Failure failure) { return failure(new WorkflowException(failure.getStatus(),failure.getCode(),failure.getMessage()),null); }
        catch(RuntimeException failure) { return failure(MedicalReviewApiContracts.unavailable(),null); }
    }

    private static ResponseEntity<Result<?>> failure(WorkflowException failure, String allow) {
        Result<Map<String,Object>> result=Result.error(failure.getStatus(),failure.getMessage());
        result.setData(MedicalReviewApiContracts.map("errorCode",failure.getErrorCode()));
        return headers(failure.getStatus(),result,allow);
    }

    private static ResponseEntity<Result<?>> headers(int status, Result<?> body, String allow) {
        ResponseEntity.BodyBuilder response=ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff");
        if(allow!=null)response.header("Allow",allow);
        return response.body(body);
    }
}
