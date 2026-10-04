package org.familyhealthcare.controller;

import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.careplan.CareExecutionReportRenderer.Export;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;

/** Strict bounded read-only report boundary; actor identity comes only from authentication. */
@RestController
@CarePlanExceptionAdvice.Api
@RequestMapping("/care-plans/reports")
public class CareExecutionReportController {
    private static final int MAX_BODY_BYTES=4096;
    private final CarePlanProperties properties;
    private final ObjectProvider<CareExecutionReportService> services;
    public CareExecutionReportController(CarePlanProperties properties,ObjectProvider<CareExecutionReportService> services){this.properties=properties;this.services=services;}
    @PostMapping("/preview") public ResponseEntity<InputStreamResource> preview(HttpServletRequest request) {
        long actor=actor();CareExecutionReportService service=service();
        byte[] content=service.preview(actor,read(request,false)).getJson();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(content.length).body(buffered(content));
    }
    @PostMapping("/export") public ResponseEntity<?> export(HttpServletRequest request) {
        long actor=actor();CareExecutionReportService service=service();
        Export file=service.export(actor,read(request,true));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.getFileName()+"\"")
                .contentLength(file.getContent().length).body(buffered(file.getContent()));
    }
    private static InputStreamResource buffered(byte[] content) {
        // Exact InputStreamResource (not a subclass): Spring 5.3 excludes it from range/206 handling.
        // Only already-authorized in-memory bytes are wrapped. A fixed description also prevents
        // HttpEntityMethodProcessor DEBUG/TRACE from logging the byte array or clinical body.
        return new InputStreamResource(new ByteArrayInputStream(content),"Care execution report");
    }
    private long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null)throw CareExecutionReportException.denied();return actor;}
    private CareExecutionReportService service(){
        if(!properties.isEnabled())throw new CareExecutionReportException(CareExecutionReportException.Code.FEATURE_DISABLED);
        CareExecutionReportService service=services.getIfAvailable();
        if(service==null)throw new CareExecutionReportException(CareExecutionReportException.Code.FEATURE_DISABLED);
        return service;
    }
    private CareExecutionReportContracts.Request read(HttpServletRequest request,boolean exporting) {
        try {
            if(request.getQueryString()!=null)throw CareExecutionReportException.invalid();
            MediaType type=request.getContentType()==null?null:MediaType.parseMediaType(request.getContentType());
            if(type==null||!"application".equalsIgnoreCase(type.getType())||!"json".equalsIgnoreCase(type.getSubtype())
                    ||(type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset())))throw CareExecutionReportException.invalid();
            if(!request.getParameterMap().isEmpty())throw CareExecutionReportException.invalid();
            if(request.getContentLengthLong()>MAX_BODY_BYTES)throw CareExecutionReportException.invalid();
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[512];
            InputStream in=request.getInputStream();int count;
            while((count=in.read(chunk,0,Math.min(chunk.length,MAX_BODY_BYTES+1-out.size())))!=-1){out.write(chunk,0,count);if(out.size()>MAX_BODY_BYTES)throw CareExecutionReportException.invalid();}
            String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
            return CareExecutionReportContracts.parse(body,exporting,Instant.now());
        }catch(IOException|IllegalArgumentException invalid){throw CareExecutionReportException.invalid();}
    }
}
