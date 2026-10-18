package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.archivestock.ArchiveStockNotificationService;
import org.familyhealthcare.service.stockworkflow.StockWorkflowAuthorizationService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController @StockWorkflowExceptionAdvice.Api @RequestMapping("/archive-stock-notifications")
public class ArchiveStockNotificationController {
    private final ArchiveStockNotificationService service;
    public ArchiveStockNotificationController(ArchiveStockNotificationService service){this.service=service;}
    @ModelAttribute public void privacy(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");}
    @GetMapping("/{stockId}") public Result<Map<String,Object>> current(@PathVariable String stockId,HttpServletRequest request){
        Map<String,String> q=StockWorkflowController.query(request,"patientId");
        return Result.ok(service.current(actor(),StockWorkflowController.positive(q.get("patientId")),StockWorkflowController.positive(stockId)));
    }
    @PostMapping(value="/commands",consumes="application/json") public Result<Map<String,Object>> command(HttpServletRequest request)throws java.io.IOException{
        StockWorkflowController.query(request);long actor=actor();return Result.ok(service.command(actor,body(request)));
    }
    private static String body(HttpServletRequest request)throws java.io.IOException{
        if(request.getContentLengthLong()>2048)throw org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.invalid();
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[512];int n,total=0;
        while((n=request.getInputStream().read(buffer,0,Math.min(buffer.length,2049-total)))!=-1){
            total+=n;if(total>2048)throw org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.invalid();out.write(buffer,0,n);
        }
        try{return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.invalid();}
    }
    @GetMapping("/{stockId}/receipts/{key}") public Result<Map<String,Object>> receipt(@PathVariable String stockId,@PathVariable String key,HttpServletRequest request){
        Map<String,String> q=StockWorkflowController.query(request,"patientId");
        return Result.ok(service.receipt(actor(),StockWorkflowController.positive(q.get("patientId")),StockWorkflowController.positive(stockId),key));
    }
    private static long actor(){Long value=CurrentUserUtil.getCurrentUserId();if(value==null)throw StockWorkflowAuthorizationService.denied();return value;}
}
