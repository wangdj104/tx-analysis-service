package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.FamilyBackupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
@RestController @RequestMapping("/care/backup")
public class FamilyBackupController {
    @Autowired private FamilyBackupService backup;
    @ModelAttribute public void questionPrivacy(javax.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");}
    @GetMapping public ResponseEntity<byte[]> download()throws Exception{return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=family-health-backup.zip").contentType(MediaType.APPLICATION_OCTET_STREAM).body(backup.exportArchive());}
    @PostMapping("/preview") public Result<Map<String,Object>>preview(@RequestParam MultipartFile file)throws Exception{return Result.ok(backup.preview(file.getBytes()));}
    @PostMapping("/restore") public Result<Map<String,Object>>restore(@RequestParam MultipartFile file)throws Exception{return Result.ok(backup.restore(file.getBytes()));}
    @ExceptionHandler(org.familyhealthcare.service.visitquestion.VisitQuestionFailure.class)
    public org.springframework.http.ResponseEntity<Result<Map<String,Object>>> questionFailure(org.familyhealthcare.service.visitquestion.VisitQuestionFailure failure){
        Result<Map<String,Object>> result=Result.error(failure.getStatus(),failure.getMessage());result.setData(java.util.Collections.singletonMap("errorCode",failure.getErrorCode()));
        return org.springframework.http.ResponseEntity.status(failure.getStatus()).header("Cache-Control","no-store, private").body(result);
    }
    @ExceptionHandler(org.familyhealthcare.service.workflow.WorkflowException.class)
    public ResponseEntity<Result<Map<String,Object>>> policyFailure(org.familyhealthcare.service.workflow.WorkflowException failure){
        Result<Map<String,Object>> result=Result.error(failure.getStatus(),failure.getMessage());result.setData(java.util.Collections.singletonMap("errorCode",failure.getErrorCode()));
        return ResponseEntity.status(failure.getStatus()).header("Cache-Control","no-store, private").body(result);
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<Map<String,Object>>> storageFailure(org.springframework.dao.DataAccessException failure){
        Result<Map<String,Object>> result=Result.error(503,"Archive storage could not be verified. No completed restore is confirmed.");result.setData(java.util.Collections.singletonMap("errorCode","ARCHIVE_STORAGE_UNAVAILABLE"));
        return ResponseEntity.status(503).header("Cache-Control","no-store, private").body(result);
    }

}
