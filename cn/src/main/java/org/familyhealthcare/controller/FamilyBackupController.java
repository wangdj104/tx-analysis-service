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
    @GetMapping public ResponseEntity<byte[]> download()throws Exception{return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=family-health-backup.zip").contentType(MediaType.APPLICATION_OCTET_STREAM).body(backup.exportArchive());}
    @PostMapping("/preview") public Result<Map<String,Object>>preview(@RequestParam MultipartFile file)throws Exception{return Result.ok(backup.preview(file.getBytes()));}
    @PostMapping("/restore") public Result<Map<String,Object>>restore(@RequestParam MultipartFile file)throws Exception{return Result.ok(backup.restore(file.getBytes()));}
}
