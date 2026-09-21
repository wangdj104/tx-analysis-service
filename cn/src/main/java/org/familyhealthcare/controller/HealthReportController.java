package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.vo.HealthReportRequestVO;
import org.familyhealthcare.service.HealthReportService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;

@RestController
@RequestMapping("/health-report")
@Api(tags = "Health ReportExport")
public class HealthReportController {

    @Autowired
    private HealthReportService healthReportService;

    @PostMapping("/generate")
    @ApiOperation("generateHealth Report (BackHTML/PDF) ")
    public ResponseEntity<?> generateReport(@RequestBody HealthReportRequestVO request) {
        try {
            if (request.getPatientId() == null) {
                return ResponseEntity.badRequest().body(Result.error(400, "Select a patient"));
            }
            String format = request.getFormat() != null ? request.getFormat() : "html";
            byte[] content = healthReportService.generateReport(request, format);

            String filename = "健康报告_" + request.getPatientId();
            HttpHeaders headers = new HttpHeaders();
            if ("pdf".equalsIgnoreCase(format)) {
                filename += ".pdf";
                headers.setContentType(MediaType.APPLICATION_PDF);
            } else {
                filename += ".html";
                headers.setContentType(MediaType.TEXT_HTML);
            }
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(filename, "UTF-8"));
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Result.error(403, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "Reportgeneratefailed: " + e.getMessage()));
        }
    }
}
