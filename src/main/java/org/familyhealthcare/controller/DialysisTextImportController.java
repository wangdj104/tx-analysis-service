package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.DialysisTextImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/dialysis/text-import")
public class DialysisTextImportController {
    @Autowired private DialysisTextImportService service;
    @PostMapping("/preview") public Result<?> preview(@RequestBody Map<String,String>b){return Result.ok(service.preview(Long.valueOf(b.get("patientId")),b.get("text")));}
    @PostMapping("/confirm") public Result<?> confirm(@RequestBody Map<String,String>b){return Result.ok(service.commit(Long.valueOf(b.get("patientId")),b.get("text")));}
}
