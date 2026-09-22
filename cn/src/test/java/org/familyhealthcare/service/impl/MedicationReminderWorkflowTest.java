package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.NotificationDeliveryService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MedicationReminderWorkflowTest {
    MedicationReminderServiceImpl service;
    MedicationReminderMapper reminders;
    MedicationIntakeMapper intakes;
    MedicationMapper medications;
    NotificationDeliveryService delivery;
    MedicationReminder reminder;
    Medication medication;

    @BeforeEach void setup() {
        service = new MedicationReminderServiceImpl();
        reminders = mock(MedicationReminderMapper.class); intakes = mock(MedicationIntakeMapper.class);
        medications = mock(MedicationMapper.class); delivery = mock(NotificationDeliveryService.class);
        ReflectionTestUtils.setField(service, "baseMapper", reminders);
        ReflectionTestUtils.setField(service, "medicationIntakeMapper", intakes);
        ReflectionTestUtils.setField(service, "medicationMapper", medications);
        ReflectionTestUtils.setField(service, "delivery", delivery);
        reminder = new MedicationReminder(); reminder.setId(1L); reminder.setUserId(7L); reminder.setPatientId(2L);
        reminder.setMedicationId(3L); reminder.setEnabled(1); reminder.setRemindTime("23:59");
        medication = new Medication(); medication.setId(3L); medication.setIsActive(1); medication.setDrugName("testMedication");
        when(reminders.selectList(any())).thenReturn(Collections.singletonList(reminder));
        when(reminders.selectById(1L)).thenReturn(reminder);
        when(medications.selectById(3L)).thenReturn(medication);
        when(intakes.selectList(any())).thenReturn(Collections.emptyList());
        when(delivery.notifyUser(any(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test void generatesWholeDayAndDoesNotDuplicate() {
        LocalDate date = LocalDate.now();
        when(intakes.insert(any())).thenAnswer(inv -> {
            MedicationIntake task = inv.getArgument(0);
            when(intakes.selectOne(any())).thenReturn(task);
            return 1;
        });
        service.ensureDailyTasks(2L, date); service.ensureDailyTasks(2L, date);
        org.mockito.ArgumentCaptor<MedicationIntake> captor = org.mockito.ArgumentCaptor.forClass(MedicationIntake.class);
        verify(intakes, times(1)).insert(captor.capture());
        assertEquals(date.atTime(23,59), captor.getValue().getScheduledAt());
        verifyNoInteractions(delivery);
    }

    @Test void disabledDrugCancelsOutstandingTask() {
        medication.setIsActive(0);
        MedicationIntake task = task("PENDING", LocalDateTime.now());
        when(intakes.selectList(any())).thenReturn(Collections.singletonList(task));
        service.ensureDailyTasks(2L, LocalDate.now());
        verify(intakes, never()).insert(any()); assertEquals("CANCELLED", task.getStatus());
    }

    @Test void respectsRepeatDays() {
        reminder.setRepeatDays(String.valueOf(LocalDate.now().plusDays(1).getDayOfWeek().getValue()));
        service.ensureDailyTasks(2L, LocalDate.now());
        verify(intakes, never()).insert(any());
    }

    @Test void restartAfterDueMinuteSendsAndMarksMissed() {
        LocalDateTime due = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0);
        reminder.setRemindTime(due.toLocalTime().toString());
        MedicationIntake task = task("PENDING", due);
        when(intakes.selectList(any())).thenReturn(Collections.singletonList(task));
        when(intakes.selectOne(any())).thenReturn(task);
        service.checkAndTriggerReminders();
        assertEquals("MISSED", task.getStatus());
        verify(delivery).notifyUser(eq(7L), eq("MEDICATION_REMINDER"), eq("Medication Reminders"), contains("testMedication"));
        service.checkAndTriggerReminders();
        verify(delivery, times(1)).notifyUser(any(), anyString(), anyString(), anyString());
    }

    @Test void snoozeWaitsAndThenResumesWithoutImmediateMissedStatus() {
        LocalDateTime due = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0);
        reminder.setRemindTime(due.toLocalTime().toString()); reminder.setLastTriggerAt(due);
        MedicationIntake task = task("SNOOZED", due); task.setSnoozeUntil(LocalDateTime.now().plusMinutes(10));
        when(intakes.selectList(any())).thenReturn(Collections.singletonList(task)); when(intakes.selectOne(any())).thenReturn(task);
        service.checkAndTriggerReminders(); verifyNoInteractions(delivery);
        task.setSnoozeUntil(LocalDateTime.now().minusMinutes(1));
        service.checkAndTriggerReminders(); assertEquals("PENDING", task.getStatus());
        service.checkAndTriggerReminders(); assertEquals("PENDING", task.getStatus());
        verify(delivery, times(1)).notifyUser(any(), anyString(), anyString(), anyString());
    }

    @Test void notificationFailureDoesNotHideMissedDose() {
        LocalDateTime due = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0);
        reminder.setRemindTime(due.toLocalTime().toString());
        MedicationIntake task = task("PENDING", due);
        when(intakes.selectList(any())).thenReturn(Collections.singletonList(task)); when(intakes.selectOne(any())).thenReturn(task);
        when(delivery.notifyUser(any(), anyString(), anyString(), anyString())).thenReturn(false);
        service.checkAndTriggerReminders(); assertEquals("MISSED", task.getStatus()); assertNull(reminder.getLastTriggerAt());
    }

    private MedicationIntake task(String status, LocalDateTime at) {
        MedicationIntake t = new MedicationIntake(); t.setId(11L); t.setPatientId(2L); t.setUserId(7L);
        t.setMedicationId(3L); t.setReminderId(1L); t.setStatus(status); t.setScheduledAt(at); return t;
    }
}
