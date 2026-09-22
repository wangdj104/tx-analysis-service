package org.familyhealthcare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.controller.MedicationController;
import org.familyhealthcare.controller.CareController;
import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.Medication;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.mapper.MedicationMapper;
import org.familyhealthcare.mapper.DryWeightMonthlyMapper;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.mapper.MedicationIntakeMapper;
import org.familyhealthcare.service.FamilyCareService;
import org.familyhealthcare.service.CareMembershipService;
import org.familyhealthcare.service.MedicationReminderService;
import org.familyhealthcare.service.MedicationStockService;
import org.familyhealthcare.mapper.CareItemMapper;
import org.familyhealthcare.entity.MedicationIntake;
import org.springframework.jdbc.core.JdbcTemplate;
import org.familyhealthcare.service.MedicationService;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClinicalRecordWorkflowTest {
    @Test void ocrPreservesDifferentAnalytesWithIdenticalResultsAndUnidentifiedRows() {
        AiOcrServiceImpl service = new AiOcrServiceImpl();
        java.util.Map<String,Object> first = new java.util.HashMap<>();
        first.put("itemName", "Test A"); first.put("resultValue", "1"); first.put("unit", "mg/L");
        java.util.Map<String,Object> second = new java.util.HashMap<>(first); second.put("itemName", "Test B");
        java.util.Map<String,Object> unnamed = new java.util.HashMap<>(first); unnamed.remove("itemName");
        java.util.List<?> result = ReflectionTestUtils.invokeMethod(service, "dedupeItems", Arrays.asList(first, second, first, unnamed, unnamed));
        assertEquals(4, result.size());
        assertEquals(1, (Integer) ReflectionTestUtils.invokeMethod(service, "parseAbnormalStatus", "偏高"));
        assertEquals(-1, (Integer) ReflectionTestUtils.invokeMethod(service, "parseAbnormalStatus", "L"));
        assertEquals(-1, (Integer) ReflectionTestUtils.invokeMethod(service, "parseAbnormalStatus", "Low"));
    }

    @Test void medicationLogCannotReferenceAnotherPatientsMedication() {
        MedicationServiceImpl service = new MedicationServiceImpl();
        MedicationMapper medications = mock(MedicationMapper.class);
        org.familyhealthcare.mapper.MedicationLogMapper logs = mock(org.familyhealthcare.mapper.MedicationLogMapper.class);
        DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service,"baseMapper",medications); ReflectionTestUtils.setField(service,"logMapper",logs);
        ReflectionTestUtils.setField(service,"dataScopeHelper",scope);
        when(scope.requirePatient(1L)).thenReturn(new Patient());
        Medication med = new Medication(); med.setPatientId(2L); when(medications.selectById(3L)).thenReturn(med);
        org.familyhealthcare.entity.MedicationLog record = new org.familyhealthcare.entity.MedicationLog();
        record.setPatientId(1L); record.setMedicationId(3L); record.setAdministrationTime(java.time.LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> service.saveLog(record)); verify(logs, never()).insert(any());
    }
    @Test void careHomeIncludesAuthorizedDoctorPatientWithoutFamilyMembership() {
        CareController controller = new CareController();DataScopeHelper scope = mock(DataScopeHelper.class);
        PatientMapper patients = mock(PatientMapper.class);FamilyCareService care = mock(FamilyCareService.class);CareMembershipService membership = mock(CareMembershipService.class);
        ReflectionTestUtils.setField(controller,"scope",scope);ReflectionTestUtils.setField(controller,"patients",patients);ReflectionTestUtils.setField(controller,"care",care);ReflectionTestUtils.setField(controller,"membership",membership);
        ReflectionTestUtils.setField(controller,"reminders",mock(MedicationReminderService.class));ReflectionTestUtils.setField(controller,"intakes",mock(MedicationIntakeMapper.class));
        when(scope.requireUserId()).thenReturn(5L);when(scope.accessiblePatientIds(5L)).thenReturn(Collections.singletonList(1L));
        Patient patient = new Patient();patient.setId(1L);patient.setName("Assigned patient");patient.setStatus(1);when(patients.selectById(1L)).thenReturn(patient);
        assertEquals(1,controller.home().getData().size());verify(membership,never()).accessiblePatients(any());
    }

    @Test void dryWeightRequiresPatientAccessAndRejectsInvalidInput() {
        DryWeightMonthlyServiceImpl service = new DryWeightMonthlyServiceImpl();DataScopeHelper scope=mock(DataScopeHelper.class);DryWeightMonthlyMapper mapper=mock(DryWeightMonthlyMapper.class);
        ReflectionTestUtils.setField(service,"dataScopeHelper",scope);ReflectionTestUtils.setField(service,"baseMapper",mapper);
        DryWeightMonthly record=new DryWeightMonthly();record.setPatientId(1L);record.setYearMonth("2026-09");record.setDryWeight(BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,()->service.saveOrUpdateByMonth(record));
        record.setDryWeight(new BigDecimal("60"));record.setYearMonth("2026-99");
        assertThrows(IllegalArgumentException.class,()->service.saveOrUpdateByMonth(record));
        record.setYearMonth("2026-09");when(scope.requirePatient(1L)).thenThrow(new IllegalStateException("Access denied"));
        assertThrows(IllegalStateException.class,()->service.saveOrUpdateByMonth(record));verify(mapper,never()).insert(any());
    }

    @Test void dryWeightUpdatePreservesOriginalOwnerAndUsesExistingMonthlyIdentity() {
        DryWeightMonthlyServiceImpl service = new DryWeightMonthlyServiceImpl();DataScopeHelper scope=mock(DataScopeHelper.class);DryWeightMonthlyMapper mapper=mock(DryWeightMonthlyMapper.class);
        ReflectionTestUtils.setField(service,"dataScopeHelper",scope);ReflectionTestUtils.setField(service,"baseMapper",mapper);when(scope.requireUserId()).thenReturn(5L);
        DryWeightMonthly existing=new DryWeightMonthly();existing.setId(1L);existing.setUserId(7L);when(mapper.selectOne(any())).thenReturn(existing);when(mapper.updateById(any())).thenReturn(1);
        DryWeightMonthly record=new DryWeightMonthly();record.setId(999L);record.setPatientId(1L);record.setYearMonth("2026-09");record.setDryWeight(new BigDecimal("60"));
        assertTrue(service.saveOrUpdateByMonth(record));assertEquals(1L,record.getId());assertEquals(7L,record.getUserId());
    }

    @Test void ordinaryReminderDoseUsesOnlyMatchingInventoryUnits() {
        MedicationStockService service=new MedicationStockService();JdbcTemplate jdbc=mock(JdbcTemplate.class);CareItemMapper plans=mock(CareItemMapper.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"items",plans);
        when(jdbc.queryForList(anyString(),eq(String.class),eq(1L),eq(3L))).thenReturn(Collections.singletonList("片"));
        MedicationIntake intake=new MedicationIntake();intake.setPatientId(1L);intake.setMedicationId(3L);intake.setDosage("1.5 tablets");
        assertEquals(new BigDecimal("1.5"),service.doseFor(intake));
        intake.setDosage("10 mg");assertNull(service.doseFor(intake));
        intake.setDosage("1");assertNull(service.doseFor(intake));
        intake.setDosage("遵医嘱");assertNull(service.doseFor(intake));
    }

    @Test void dailyReportFilterMatchesExactDayAndMonthlyFilterUsesBoundaries() {
        MedicalRecordServiceImpl service = new MedicalRecordServiceImpl();
        MedicalRecordMapper mapper = mock(MedicalRecordMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "dataScopeHelper", mock(DataScopeHelper.class));
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        service.listRecords(1L, null, null, "2026-09-22");
        ArgumentCaptor<QueryWrapper<MedicalRecord>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("record_date ="));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(LocalDate.of(2026,9,22)));

        clearInvocations(mapper);
        service.listRecords(1L, null, null, "2026-09");
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("record_date >=") && sql.contains("record_date <"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(LocalDate.of(2026,9,1)));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(LocalDate.of(2026,10,1)));
    }

    @Test void vitalSignFiltersIncludeCombinedEntriesAndNormalizeLegacyType() {
        BpSelfMonitorRecordServiceImpl service = new BpSelfMonitorRecordServiceImpl();
        BpSelfMonitorRecordMapper mapper = mock(BpSelfMonitorRecordMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "dataScopeHelper", mock(DataScopeHelper.class));
        for (String type : Arrays.asList("BP", "BG")) {
            clearInvocations(mapper);
            BpSelfMonitorRecord row = new BpSelfMonitorRecord(); row.setMeasureType("BOTH");
            when(mapper.selectList(any())).thenReturn(Collections.singletonList(row));
            assertEquals("BP_BG", service.listByPatientAndType(1L, type).get(0).getMeasureType());
            ArgumentCaptor<QueryWrapper<BpSelfMonitorRecord>> query = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(mapper).selectList(query.capture());
            assertTrue(query.getValue().getSqlSegment().contains("measure_type IN"));
            assertTrue(query.getValue().getParamNameValuePairs().values().containsAll(Arrays.asList(type,"BP_BG","BOTH")));
        }
    }

    @Test void editingVitalsCannotReassignPatientOrOwner() {
        BpSelfMonitorRecordServiceImpl service = new BpSelfMonitorRecordServiceImpl();
        BpSelfMonitorRecordMapper mapper = mock(BpSelfMonitorRecordMapper.class);
        DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "dataScopeHelper", scope);
        BpSelfMonitorRecord existing = new BpSelfMonitorRecord(); existing.setId(3L);existing.setPatientId(1L);existing.setUserId(10L);
        when(mapper.selectById(3L)).thenReturn(existing);when(mapper.updateById(any())).thenReturn(1);
        BpSelfMonitorRecord edited = new BpSelfMonitorRecord(); edited.setId(3L);edited.setPatientId(999L);edited.setUserId(999L);edited.setMeasureType("BOTH");
        assertTrue(service.updateOwned(edited));
        verify(scope).requirePatientOrOwner(1L,10L);
        assertEquals(1L,edited.getPatientId());assertEquals(10L,edited.getUserId());assertEquals("BP_BG",edited.getMeasureType());
    }

    @Test void medicationBatchAuthorizesEveryPatientBeforeWritingAnyRecord() {
        MedicationServiceImpl service = new MedicationServiceImpl();
        MedicationMapper mapper = mock(MedicationMapper.class);DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service,"baseMapper",mapper);ReflectionTestUtils.setField(service,"dataScopeHelper",scope);
        when(scope.requireUserId()).thenReturn(5L);when(scope.requirePatient(1L)).thenReturn(new Patient());
        when(scope.requirePatient(2L)).thenThrow(new IllegalStateException("Access denied"));
        Medication first = new Medication();first.setPatientId(1L);Medication second = new Medication();second.setPatientId(2L);
        assertThrows(IllegalStateException.class,()->service.saveMedicationsBatch(Arrays.asList(first,second)));
        verify(mapper,never()).insert(any());
    }

    @Test void medicationCreateChecksPatientAndClearsClientSuppliedId() {
        MedicationController controller = new MedicationController();MedicationService writer = mock(MedicationService.class);DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(controller,"medicationService",writer);ReflectionTestUtils.setField(controller,"dataScopeHelper",scope);
        when(scope.requireUserId()).thenReturn(5L);when(writer.save(any())).thenReturn(true);
        Medication record = new Medication();record.setId(77L);record.setPatientId(1L);
        controller.saveMedication(record);
        verify(scope).requirePatient(1L);assertNull(record.getId());assertEquals(5L,record.getUserId());
    }

    @Test void medicationEditPreservesOriginalPatientAndOwner() {
        MedicationController controller = new MedicationController();MedicationService writer = mock(MedicationService.class);DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(controller,"medicationService",writer);ReflectionTestUtils.setField(controller,"dataScopeHelper",scope);
        Medication existing = new Medication();existing.setId(77L);existing.setPatientId(1L);existing.setUserId(5L);
        when(writer.getById(77L)).thenReturn(existing);when(writer.updateById(any())).thenReturn(true);
        Medication edited = new Medication();edited.setId(77L);edited.setPatientId(999L);edited.setUserId(999L);
        controller.updateMedication(edited);
        verify(scope).requirePatientOrOwner(1L,5L);assertEquals(1L,edited.getPatientId());assertEquals(5L,edited.getUserId());
    }
}
