package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.DialysisRecordMapper;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DialysisRecordUniquenessTest {
    private DialysisRecordServiceImpl service;
    private DialysisRecordMapper mapper;
    private DataScopeHelper scope;

    @BeforeEach
    void setUp() {
        service = new DialysisRecordServiceImpl();
        mapper = mock(DialysisRecordMapper.class);
        scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "dataScopeHelper", scope);
        when(scope.requireUserId()).thenReturn(7L);
        when(scope.requirePatient(11L)).thenReturn(new Patient());
    }

    @Test
    void createRejectsAnotherRecordForTheSamePatientAndDate() {
        when(mapper.selectCount(any())).thenReturn(1L);
        DialysisRecord row = row(null, 11L, "2026-09-07");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveRecord(row));

        assertEquals("2026-09-07  already has a dialysis record. Edit the existing record instead of creating a duplicate.", error.getMessage());
        verify(mapper, never()).insert(any());
    }

    @Test
    void editRejectsChangingARecordToAnotherExistingDate() {
        DialysisRecord existing = row(8L, 11L, "2026-09-03");
        when(mapper.selectById(8L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(1L);
        DialysisRecord update = row(8L, 11L, "2026-09-07");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateRecord(update));

        assertEquals("2026-09-07  already has a dialysis record. Edit the existing record instead of creating a duplicate.", error.getMessage());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void createRequiresPatientAndDate() {
        assertThrows(IllegalArgumentException.class,
                () -> service.saveRecord(row(null, null, "2026-09-07")));
        DialysisRecord missingDate = row(null, 11L, "2026-09-07");
        missingDate.setRecordDate(null);
        assertThrows(IllegalArgumentException.class, () -> service.saveRecord(missingDate));
    }

    private DialysisRecord row(Long id, Long patientId, String date) {
        DialysisRecord row = new DialysisRecord();
        row.setId(id);
        row.setPatientId(patientId);
        row.setRecordDate(LocalDate.parse(date));
        return row;
    }
}
