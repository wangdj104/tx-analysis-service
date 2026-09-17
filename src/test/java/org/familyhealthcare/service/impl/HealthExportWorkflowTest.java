package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HealthExportWorkflowTest {
    @Test void reportExcludesMeasurementsOutsideSelectedMonth() throws Exception {
        HealthReportServiceImpl service=new HealthReportServiceImpl();
        BpSelfMonitorRecordService measurements=mock(BpSelfMonitorRecordService.class);
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));
        ReflectionTestUtils.setField(service,"patientMapper",mock(PatientMapper.class));
        ReflectionTestUtils.setField(service,"patientClinicalService",mock(PatientClinicalService.class));
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",measurements);
        BpSelfMonitorRecord included=measurement("2026-09-10"),excluded=measurement("2026-08-10");
        when(measurements.listByPatient(2L)).thenReturn(Arrays.asList(included,excluded));
        HealthReportRequestVO request=new HealthReportRequestVO();request.setPatientId(2L);request.setReportType("bp_monitor");request.setTimeType("month");request.setTimeValue("2026-09");
        String report=new String(service.generateReport(request,"html"),StandardCharsets.UTF_8);
        assertTrue(report.contains("2026-09-10"));assertFalse(report.contains("2026-08-10"));
    }

    @Test void csvFiltersMeasurementsAndExportsActualMedicationHistory() throws Exception {
        DataExportServiceImpl service=new DataExportServiceImpl();
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));
        BpSelfMonitorRecordService measurements=mock(BpSelfMonitorRecordService.class);
        MedicationService medication=mock(MedicationService.class);MedicationIntakeMapper intakes=mock(MedicationIntakeMapper.class);
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",measurements);ReflectionTestUtils.setField(service,"medicationService",medication);ReflectionTestUtils.setField(service,"intakeMapper",intakes);
        when(measurements.listByPatient(2L)).thenReturn(Arrays.asList(measurement("2026-09-10"),measurement("2026-08-10")));
        DataExportRequestVO request=new DataExportRequestVO();request.setPatientId(2L);request.setTimeType("month");request.setTimeValue("2026-09");request.setDataType("bp_self_monitor");
        String csv=new String(service.exportCsv(request),StandardCharsets.UTF_8);assertTrue(csv.contains("2026-09-10"));assertFalse(csv.contains("2026-08-10"));
        Medication med=new Medication();med.setDrugName("Test medication, with comma");when(medication.getById(3L)).thenReturn(med);
        MedicationLog log=new MedicationLog();log.setMedication(med);log.setAdministrationTime(LocalDateTime.of(2026,9,10,8,0));log.setDosage("1 tablet");
        when(medication.listLogs(2L,null)).thenReturn(Collections.singletonList(log));
        MedicationIntake intake=new MedicationIntake();intake.setMedicationId(3L);intake.setScheduledAt(LocalDateTime.of(2026,9,10,9,0));intake.setActionAt(LocalDateTime.of(2026,9,10,9,5));intake.setStatus("TAKEN");
        when(intakes.selectList(any())).thenReturn(Collections.singletonList(intake));request.setDataType("medication");
        csv=new String(service.exportCsv(request),StandardCharsets.UTF_8);
        assertTrue(csv.contains("Actual intake time"));assertTrue(csv.contains("2026-09-10T09:05"));assertTrue(csv.contains("Manual record"));assertTrue(csv.contains("Reminder check-in"));assertTrue(csv.contains("\"Test medication, with comma\""));
    }

    private BpSelfMonitorRecord measurement(String date) {BpSelfMonitorRecord r=new BpSelfMonitorRecord();r.setRecordDate(LocalDate.parse(date));r.setSystolicBp(120);r.setDiastolicBp(80);return r;}
}
