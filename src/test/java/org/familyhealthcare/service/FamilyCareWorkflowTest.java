package org.familyhealthcare.service;

import org.familyhealthcare.controller.FamilyHealthController;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FamilyCareWorkflowTest {
    @Test void savingNewFamilyMemberTargetDoesNotReusePreviousRecordId() {
        FamilyHealthController controller = new FamilyHealthController();
        DataScopeHelper scope = mock(DataScopeHelper.class); PatientHealthTargetMapper mapper = mock(PatientHealthTargetMapper.class);
        ReflectionTestUtils.setField(controller,"scope",scope); ReflectionTestUtils.setField(controller,"targetMapper",mapper);
        when(scope.requireUserId()).thenReturn(1L);
        PatientHealthTarget target = new PatientHealthTarget();target.setId(11L);target.setPatientId(2L);
        controller.saveTarget(target);
        verify(mapper).insert(target); verify(mapper,never()).updateById(any());assertNull(target.getId());assertEquals(2L,target.getPatientId());
    }

    @Test void savingExistingTargetUsesItsOwnIdentity() {
        FamilyHealthController controller = new FamilyHealthController();
        DataScopeHelper scope = mock(DataScopeHelper.class);PatientHealthTargetMapper mapper = mock(PatientHealthTargetMapper.class);
        ReflectionTestUtils.setField(controller,"scope",scope);ReflectionTestUtils.setField(controller,"targetMapper",mapper);
        PatientHealthTarget existing=new PatientHealthTarget();existing.setId(22L);existing.setUserId(1L);
        when(mapper.selectOne(any())).thenReturn(existing);
        PatientHealthTarget form=new PatientHealthTarget();form.setId(11L);form.setPatientId(2L);
        controller.saveTarget(form);assertEquals(22L,form.getId());verify(mapper).updateById(form);verify(mapper,never()).insert(any());
    }

    @Test void schedulesTrackCreationAndDeletionOfActualRecordsButPreserveCancellation() {
        DialysisScheduleService service=new DialysisScheduleService();
        DialysisScheduleMapper schedules=mock(DialysisScheduleMapper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);
        DataScopeHelper scope=mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service,"schedules",schedules);ReflectionTestUtils.setField(service,"records",records);ReflectionTestUtils.setField(service,"scope",scope);
        DialysisSchedule row=new DialysisSchedule();row.setId(1L);row.setPatientId(2L);row.setScheduleDate(LocalDate.now());row.setStatus("PLANNED");
        DialysisRecord actual=new DialysisRecord();actual.setId(5L);actual.setRecordDate(LocalDate.now());
        when(schedules.selectCount(any())).thenReturn(1L);when(schedules.selectList(any())).thenReturn(Collections.singletonList(row));when(records.selectList(any())).thenReturn(Collections.singletonList(actual));
        service.list(2L);assertEquals("COMPLETED",row.getStatus());assertEquals(5L,row.getCompletedRecordId());
        when(records.selectList(any())).thenReturn(Collections.emptyList());service.list(2L);
        assertEquals("PLANNED",row.getStatus());assertNull(row.getCompletedRecordId());
        row.setStatus("CANCELLED");when(records.selectList(any())).thenReturn(Collections.singletonList(actual));service.list(2L);
        assertEquals("CANCELLED",row.getStatus());
    }

    @Test void emptyMonthsGenerateMondayAndThursdaySchedulesThroughNextMonth() {
        DialysisScheduleService service=new DialysisScheduleService();
        DialysisScheduleMapper schedules=mock(DialysisScheduleMapper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);DataScopeHelper scope=mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service,"schedules",schedules);ReflectionTestUtils.setField(service,"records",records);ReflectionTestUtils.setField(service,"scope",scope);
        when(scope.requireUserId()).thenReturn(7L);when(schedules.selectCount(any())).thenReturn(0L);when(schedules.selectList(any())).thenReturn(Collections.emptyList());when(records.selectList(any())).thenReturn(Collections.emptyList());

        service.list(2L);

        org.mockito.ArgumentCaptor<DialysisSchedule> inserted=org.mockito.ArgumentCaptor.forClass(DialysisSchedule.class);
        verify(schedules,atLeastOnce()).insert(inserted.capture());
        LocalDate today=LocalDate.now();YearMonth nextMonth=YearMonth.from(today).plusMonths(1);
        for(DialysisSchedule row:inserted.getAllValues()){
            assertEquals(2L,row.getPatientId());assertEquals(7L,row.getUserId());assertEquals("PLANNED",row.getStatus());
            assertTrue(row.getScheduleDate().getDayOfWeek()==DayOfWeek.MONDAY || row.getScheduleDate().getDayOfWeek()==DayOfWeek.THURSDAY);
            assertFalse(row.getScheduleDate().isBefore(today));assertFalse(row.getScheduleDate().isAfter(nextMonth.atEndOfMonth()));
        }
    }

    @Test void timelineReflectsSourceEditsAndDeletesWithoutCreatingCopies() {
        HealthTimelineService service=new HealthTimelineService();
        HealthEventMapper events=mock(HealthEventMapper.class);BpSelfMonitorRecordMapper measurements=mock(BpSelfMonitorRecordMapper.class);
        DialysisRecordMapper dialysis=mock(DialysisRecordMapper.class);MedicationIntakeMapper intakes=mock(MedicationIntakeMapper.class);
        MedicationLogMapper logs=mock(MedicationLogMapper.class);MedicationMapper medications=mock(MedicationMapper.class);
        ReflectionTestUtils.setField(service,"events",events);ReflectionTestUtils.setField(service,"measurements",measurements);
        ReflectionTestUtils.setField(service,"dialysis",dialysis);ReflectionTestUtils.setField(service,"intakes",intakes);
        ReflectionTestUtils.setField(service,"logs",logs);ReflectionTestUtils.setField(service,"medications",medications);
        HealthEvent note=new HealthEvent();note.setId(1L);note.setEventDate(LocalDate.now().minusDays(1));note.setTitle("Yesterday's symptom");note.setSourceType("MANUAL");
        BpSelfMonitorRecord measurement=new BpSelfMonitorRecord();measurement.setId(1L);measurement.setRecordDate(LocalDate.now());measurement.setRecordTime("08:00");measurement.setSystolicBp(120);
        when(events.selectList(any())).thenReturn(Collections.singletonList(note));when(measurements.selectList(any())).thenReturn(Collections.singletonList(measurement));
        List<HealthEvent> first=service.list(2L,null,null,200);assertEquals(2,first.size());assertEquals("MEASUREMENT",first.get(0).getSourceType());
        measurement.setSystolicBp(125);assertTrue(service.list(2L,null,null,200).get(0).getSummary().contains("125"));
        when(measurements.selectList(any())).thenReturn(Collections.emptyList());assertEquals(1,service.list(2L,null,null,200).size());
        verify(events,never()).insert(any());
    }

    @Test void manualEventsCanBeBackdatedEditedAndDeleted() {
        FamilyHealthController controller=new FamilyHealthController();HealthEventMapper events=mock(HealthEventMapper.class);DataScopeHelper scope=mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(controller,"eventMapper",events);ReflectionTestUtils.setField(controller,"scope",scope);
        when(scope.requireUserId()).thenReturn(1L);
        HealthEvent e=new HealthEvent();e.setPatientId(2L);e.setEventDate(LocalDate.now().minusDays(3));e.setTitle("supplementrecordthenvisit");e.setEventType("VISIT");e.setEventTime("08:30");
        assertEquals(200,controller.event(e).getCode());verify(events).insert(e);
        e.setId(4L);when(events.selectById(4L)).thenReturn(e);e.setTitle("Editinstructions");controller.event(e);verify(events).updateById(e);
        controller.deleteEvent(4L);verify(events).deleteById(4L);
    }
}
