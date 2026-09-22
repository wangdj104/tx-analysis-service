package org.familyhealthcare.service;

import org.familyhealthcare.controller.DataExportController;
import org.familyhealthcare.controller.HealthReportController;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.vo.DataExportRequestVO;
import org.familyhealthcare.vo.HealthReportRequestVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadResponseTest {
    @Test void csvErrorsUseJsonAndCorrectStatusInsteadOfInvalidDownloadBytes() throws Exception {
        DataExportController controller=new DataExportController();
        DataExportService service=mock(DataExportService.class);
        ReflectionTestUtils.setField(controller,"dataExportService",service);
        DataExportRequestVO request=new DataExportRequestVO();
        ResponseEntity<?> missing=controller.exportCsv(request);
        assertEquals(400,missing.getStatusCodeValue());
        assertEquals(MediaType.APPLICATION_JSON,missing.getHeaders().getContentType());
        assertTrue(missing.getBody() instanceof Result);
        request.setPatientId(1L);
        when(service.exportCsv(request)).thenThrow(new IllegalStateException("Access denied"));
        assertEquals(403,controller.exportCsv(request).getStatusCodeValue());
        doThrow(new IllegalArgumentException("Invalid range")).when(service).exportCsv(request);
        assertEquals(400,controller.exportCsv(request).getStatusCodeValue());
        doThrow(new RuntimeException("sensitive internal detail")).when(service).exportCsv(request);
        ResponseEntity<?> failed=controller.exportCsv(request);
        assertEquals(500,failed.getStatusCodeValue());
        assertFalse(((Result<?>)failed.getBody()).getMsg().contains("sensitive"));
    }
    @Test void successfulCsvKeepsBinaryPayload() throws Exception {
        DataExportController controller=new DataExportController();DataExportService service=mock(DataExportService.class);
        ReflectionTestUtils.setField(controller,"dataExportService",service);
        DataExportRequestVO request=new DataExportRequestVO();request.setPatientId(1L);
        byte[] content="date,value\n2026-09-22,120".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.exportCsv(request)).thenReturn(content);
        ResponseEntity<?> response=controller.exportCsv(request);
        assertEquals(200,response.getStatusCodeValue());assertSame(content,response.getBody());
        assertEquals("text/csv",response.getHeaders().getContentType().toString());
    }
    @Test void reportFormatErrorsAndPermissionFailuresAreNotReportedAsDownloads() throws Exception {
        HealthReportController controller=new HealthReportController();HealthReportService service=mock(HealthReportService.class);
        ReflectionTestUtils.setField(controller,"healthReportService",service);
        HealthReportRequestVO request=new HealthReportRequestVO();request.setPatientId(1L);request.setFormat("exe");
        assertEquals(400,controller.generateReport(request).getStatusCodeValue());verifyNoInteractions(service);
        request.setFormat("pdf");
        when(service.generateReport(request,"pdf")).thenThrow(new IllegalStateException("Access denied"));
        ResponseEntity<?> denied=controller.generateReport(request);assertEquals(403,denied.getStatusCodeValue());
        assertEquals(MediaType.APPLICATION_JSON,denied.getHeaders().getContentType());
        doReturn(new byte[]{37,80,68,70}).when(service).generateReport(request,"pdf");
        assertEquals(MediaType.APPLICATION_PDF,controller.generateReport(request).getHeaders().getContentType());
    }
}
