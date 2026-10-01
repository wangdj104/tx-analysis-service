package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.AlertRecordMapper;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.mapper.DialysisRecordMapper;
import org.familyhealthcare.mapper.DialysisScheduleMapper;
import org.familyhealthcare.mapper.DryWeightMonthlyMapper;
import org.familyhealthcare.mapper.HealthEventMapper;
import org.familyhealthcare.mapper.MedicationIntakeMapper;
import org.familyhealthcare.mapper.MedicationMapper;
import org.familyhealthcare.mapper.PatientHealthTargetMapper;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.MonitoringSnapshotVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceImplTest {

    @InjectMocks private MonitoringServiceImpl service;
    @Mock private DataScopeHelper dataScopeHelper;
    @Mock private BpSelfMonitorRecordMapper monitorMapper;
    @Mock private AlertRecordMapper alertMapper;
    @Mock private MedicationIntakeMapper intakeMapper;
    @Mock private MedicationMapper medicationMapper;
    @Mock private DialysisScheduleMapper scheduleMapper;
    @Mock private DialysisRecordMapper dialysisMapper;
    @Mock private DryWeightMonthlyMapper dryWeightMapper;
    @Mock private HealthEventMapper eventMapper;
    @Mock private PatientHealthTargetMapper targetMapper;
    @Mock private org.familyhealthcare.service.MedicationReminderService reminderService;
    @Mock private org.familyhealthcare.service.HealthTimelineService timelineService;
    @Mock private org.familyhealthcare.service.DialysisScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        Patient patient = new Patient();
        patient.setId(1L);
        patient.setName("testPatient");
        patient.setBirthDate(LocalDate.of(1980, 1, 1));
        when(dataScopeHelper.requirePatient(1L)).thenReturn(patient);
        when(targetMapper.selectOne(any())).thenReturn(null);
        when(alertMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(intakeMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(scheduleService.list(1L)).thenReturn(Collections.emptyList());
        when(dialysisMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dryWeightMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(timelineService.list(1L, null, null, 30)).thenReturn(Collections.emptyList());
    }

    @Test
    void shouldKeepStableWhenLatestVitalsAreNormal() {
        when(monitorMapper.selectList(any())).thenReturn(Collections.singletonList(vital(126, 78, "5.3")));

        MonitoringSnapshotVO result = service.getSnapshot(1L, 7);

        assertEquals("STABLE", result.getOverallStatus());
        assertEquals("Health status is stable", result.getStatusLabel());
        assertEquals(50, result.getMetrics().getDataCompleteness());
    }

    @Test
    void shouldEscalateCriticalBloodPressure() {
        when(monitorMapper.selectList(any())).thenReturn(Collections.singletonList(vital(185, 122, "5.3")));

        MonitoringSnapshotVO result = service.getSnapshot(1L, 7);

        assertEquals("CRITICAL", result.getOverallStatus());
        assertEquals("Immediate attention required", result.getStatusLabel());
    }

    @Test
    void glucoseUnitsUseEquivalentSeverityWithoutChangingStoredReadings() {
        for (String[] sample : new String[][]{{"6", "NORMAL"}, {"3.9", "NORMAL"}, {"6.1", "NORMAL"}, {"3.0", "WARNING"}, {"2.9", "CRITICAL"}, {"16.7", "WARNING"}, {"16.8", "CRITICAL"}}) {
            for (String unit : new String[]{"mmol/L", "mg/dL", "MG/DL"}) {
                BigDecimal value = new BigDecimal(sample[0]);
                if ("mg/dL".equalsIgnoreCase(unit)) value = value.multiply(new BigDecimal("18"));
                BpSelfMonitorRecord row = vital(120, 80, value.toPlainString()); row.setBgUnit(unit);
                when(monitorMapper.selectList(any())).thenReturn(Collections.singletonList(row));
                MonitoringSnapshotVO result = service.getSnapshot(1L, 7);
                MonitoringSnapshotVO.Signal signal = result.getSignals().stream().filter(item -> "glucose".equals(item.getKey())).findFirst().orElseThrow(AssertionError::new);
                assertEquals(sample[1], signal.getStatus(), sample[0] + " " + unit);
                assertEquals(value, result.getVitalTrend().get(0).getGlucose());
                assertEquals(unit, result.getVitalTrend().get(0).getGlucoseUnit());
            }
        }
    }

    @Test
    void postMealAliasesHaveTheSameExistingThresholdsInEitherUnit() {
        for (String period : new String[]{"After Meal", "After Meal2h"}) {
            for (String[] sample : new String[][]{{"7", "NORMAL"}, {"7.8", "NORMAL"}, {"7.9", "WARNING"}, {"2", "CRITICAL"}}) {
                BpSelfMonitorRecord row = vital(120, 80, new BigDecimal(sample[0]).multiply(new BigDecimal("18")).toPlainString());
                row.setBgUnit("mg/dL"); row.setMeasurePeriod(period);
                when(monitorMapper.selectList(any())).thenReturn(Collections.singletonList(row));
                MonitoringSnapshotVO result = service.getSnapshot(1L, 7);
                assertEquals(sample[1], result.getSignals().stream().filter(item -> "glucose".equals(item.getKey())).findFirst().orElseThrow(AssertionError::new).getStatus());
            }
        }
    }

    @Test
    void missingLegacyUnitsDefaultToMmolButUnsupportedUnitsAreNotAssessed() {
        for (String unit : new String[]{null, "", "  ", "mmol/L", "mg/L"}) {
            BpSelfMonitorRecord row = vital(120, 80, "6"); row.setBgUnit(unit);
            when(monitorMapper.selectList(any())).thenReturn(Collections.singletonList(row));
            MonitoringSnapshotVO result = service.getSnapshot(1L, 7);
            assertEquals("mg/L".equals(unit) ? "NO_DATA" : "NORMAL", result.getSignals().stream().filter(item -> "glucose".equals(item.getKey())).findFirst().orElseThrow(AssertionError::new).getStatus());
            assertEquals(new BigDecimal("6"), result.getVitalTrend().get(0).getGlucose());
            assertEquals(unit, result.getVitalTrend().get(0).getGlucoseUnit());
        }
    }

    private BpSelfMonitorRecord vital(int systolic, int diastolic, String glucose) {
        BpSelfMonitorRecord row = new BpSelfMonitorRecord();
        row.setPatientId(1L);
        row.setRecordDate(LocalDate.now());
        row.setRecordTime("08:30");
        row.setMeasureType("BP");
        row.setSystolicBp(systolic);
        row.setDiastolicBp(diastolic);
        row.setBloodGlucose(new BigDecimal(glucose));
        row.setBgUnit("mmol/L");
        row.setMeasurePeriod("Fasting");
        return row;
    }
}
