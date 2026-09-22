package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.DialysisTextImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
@RestController @RequestMapping("/dialysis/text-import")
public class DialysisTextImportController {
    @Autowired private DialysisTextImportService service;
    @Data public static class ImportRequest {
        @NotNull @Positive private Long patientId;
        @Size(max=100000) private String text;
    }
    @PostMapping("/preview") public Result<?> preview(@Valid @RequestBody ImportRequest request){return Result.ok(service.preview(request.getPatientId(),request.getText()));}
    @PostMapping("/confirm") public Result<?> confirm(@Valid @RequestBody ImportRequest request){return Result.ok(service.commit(request.getPatientId(),request.getText()));}
}
