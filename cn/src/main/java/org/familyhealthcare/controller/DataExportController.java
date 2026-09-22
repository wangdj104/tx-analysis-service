package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.DataExportService;
import org.familyhealthcare.vo.DataExportRequestVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;

@RestController
@RequestMapping("/data-export")
@Api(tags = "Data Export")
public class DataExportController {

    @Autowired
    private DataExportService dataExportService;

    @PostMapping("/csv")
    @ApiOperation("Exportdatafor CSVformat")
    public ResponseEntity<?> exportCsv(@RequestBody DataExportRequestVO request) {
        try {
            if (request.getPatientId() == null) {
                return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Result.error(400, "Select a patient"));
            }
            byte[] content = dataExportService.exportCsv(request);

            String dataType = request.getDataType() != null ? request.getDataType() : "dialysis";
            String filename = URLEncoder.encode(dataType + "_Data Export.csv", "UTF-8");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("text", "csv"));
            headers.setContentDispositionFormData("attachment", filename);
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON).body(Result.error(403, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Result.error(400, "Invalid request parameter."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(Result.error(500, "The system could not process the request. Please try again later."));
        }
    }
}
