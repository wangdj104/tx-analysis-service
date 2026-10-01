package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MeasurementValidationTest {
    BpSelfMonitorRecordServiceImpl service;
    BpSelfMonitorRecordMapper mapper;
    @BeforeEach void setup() {
        service = new BpSelfMonitorRecordServiceImpl(); mapper = mock(BpSelfMonitorRecordMapper.class);
        DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "dataScopeHelper", scope);
        when(scope.requireUserId()).thenReturn(7L); when(mapper.insert(any())).thenReturn(1);
    }
    private BpSelfMonitorRecord record(String type) {
        BpSelfMonitorRecord row = new BpSelfMonitorRecord(); row.setPatientId(1L);
        row.setRecordDate(LocalDate.of(2026,9,22)); row.setMeasureType(type); return row;
    }
    @Test void emptyAndIncompleteMeasurementsNeverReachPersistence() {
        for (String type : new String[]{"BP", "BG", "BP_BG", "BOTH", null, "unknown"}) {
            assertThrows(IllegalArgumentException.class, () -> service.saveOwned(record(type)), String.valueOf(type));
        }
        BpSelfMonitorRecord row = record("BP"); row.setSystolicBp(120);
        assertThrows(IllegalArgumentException.class, () -> service.saveOwned(row));
        row.setDiastolicBp(80); row.setRecordDate(null);
        assertThrows(IllegalArgumentException.class, () -> service.saveOwned(row));
        verify(mapper, never()).insert(any());
    }
    @Test void glucoseMustBePositiveAndUseAKnownUnit() {
        BpSelfMonitorRecord row = record("BG"); row.setBgUnit("mg/dL");
        for (String value : new String[]{"0", "-0.1", "-108"}) {
            row.setBloodGlucose(new BigDecimal(value));
            assertThrows(IllegalArgumentException.class, () -> service.saveOwned(row));
        }
        row.setBloodGlucose(new BigDecimal("108")); row.setBgUnit("mg/L");
        assertThrows(IllegalArgumentException.class, () -> service.saveOwned(row));
        verify(mapper, never()).insert(any());
    }
    @Test void existingAcceptedBpBoundsRemainInclusive() {
        for (int[] values : new int[][]{{40,20},{300,200},{120,80}}) {
            BpSelfMonitorRecord row = record("BP"); row.setSystolicBp(values[0]); row.setDiastolicBp(values[1]);
            assertTrue(service.saveOwned(row));
        }
        for (int[] values : new int[][]{{39,80},{301,80},{120,19},{120,201},{-1,80}}) {
            BpSelfMonitorRecord row = record("BP"); row.setSystolicBp(values[0]); row.setDiastolicBp(values[1]);
            assertThrows(IllegalArgumentException.class, () -> service.saveOwned(row));
        }
        verify(mapper, times(3)).insert(any());
    }
    @Test void validMixedMeasurementsKeepTheirOriginalValueAndLegacyAlias() {
        BpSelfMonitorRecord row = record("BOTH"); row.setSystolicBp(120); row.setDiastolicBp(80);
        row.setBloodGlucose(new BigDecimal("108")); row.setBgUnit("mg/dL");
        assertTrue(service.saveOwned(row));
        assertEquals("BP_BG", row.getMeasureType()); assertEquals(new BigDecimal("108"), row.getBloodGlucose()); assertEquals("mg/dL", row.getBgUnit());
        BpSelfMonitorRecord legacy = record("BG"); legacy.setBloodGlucose(new BigDecimal("6"));
        assertTrue(service.saveOwned(legacy));
    }
    @Test void partialEditsValidateEffectiveValuesAndRejectInvalidReplacements() {
        BpSelfMonitorRecord existing = record("BG"); existing.setId(3L); existing.setUserId(7L);
        existing.setBloodGlucose(new BigDecimal("108")); existing.setBgUnit("mg/dL");
        when(mapper.selectById(3L)).thenReturn(existing); when(mapper.updateById(any())).thenReturn(1);
        BpSelfMonitorRecord patch = new BpSelfMonitorRecord(); patch.setId(3L); patch.setRemark("Review note");
        assertTrue(service.updateOwned(patch));
        assertNull(patch.getBloodGlucose()); assertNull(patch.getBgUnit());
        patch.setBloodGlucose(BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> service.updateOwned(patch));
        patch.setBloodGlucose(new BigDecimal("108")); patch.setBgUnit("mg/L");
        assertThrows(IllegalArgumentException.class, () -> service.updateOwned(patch));
        verify(mapper, times(1)).updateById(any());
    }
}
