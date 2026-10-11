package org.familyhealthcare.task;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.support.PatientAuthorizationFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FamilyCareTaskAuthorizationTest {
    PatientAuthorizationFixture f;
    FamilyCareTask task;
    CareItemMapper items;
    FamilyCareService care;
    CareMembershipService membership;
    NotificationDeliveryService delivery;
    MedicationStockService stock;
    JdbcTemplate taskJdbc;
    @BeforeEach void setup() {
        f = new PatientAuthorizationFixture(); f.member(1L,11L); f.member(1L,12L);
        items=mock(CareItemMapper.class);care=mock(FamilyCareService.class);membership=mock(CareMembershipService.class);
        delivery=mock(NotificationDeliveryService.class);stock=mock(MedicationStockService.class);taskJdbc=mock(JdbcTemplate.class);
        when(items.selectList(any())).thenReturn(Collections.emptyList()); when(f.patients.selectList(any())).thenReturn(Collections.emptyList());
        when(care.coordinator(1L)).thenReturn(11L); when(care.delayMinutes(1L)).thenReturn(15); when(membership.canAssign(1L,11L)).thenReturn(true);
        when(delivery.notifyUser(anyLong(),anyString(),anyString(),anyString())).thenReturn(true);
        // Use Spring's ordinary injection so each implementation declares its own required dependencies.
        DefaultListableBeanFactory beans=new DefaultListableBeanFactory();
        beans.registerSingleton("items",items);beans.registerSingleton("care",care);beans.registerSingleton("membership",membership);beans.registerSingleton("delivery",delivery);
        beans.registerSingleton("stock",stock);beans.registerSingleton("patients",f.patients);beans.registerSingleton("jdbc",taskJdbc);
        beans.registerSingleton("reminders",mock(MedicationReminderMapper.class));beans.registerSingleton("audience",f.audience);
        AutowiredAnnotationBeanPostProcessor injection=new AutowiredAnnotationBeanPostProcessor();injection.setBeanFactory(beans);beans.addBeanPostProcessor(injection);
        task=new FamilyCareTask();beans.autowireBean(task);
    }
    @AfterEach void cleanup() { f.close(); }

    @ParameterizedTest @CsvSource({"FAMILY_CARE_REMINDER,REVOKED", "FAMILY_CARE_REMINDER,EXPIRED", "FAMILY_CARE_REMINDER,MODULE", "CARE_FOLLOW_UP,REVOKED", "CARE_FOLLOW_UP,EXPIRED", "CARE_FOLLOW_UP,MODULE", "MEDICATION_MISSED,REVOKED", "MEDICATION_MISSED,EXPIRED", "MEDICATION_MISSED,MODULE"})
    void staleRecipientCannotReceiveOrProduceSuccessfulNotificationMarkers(String event,String restriction) {
        prepare(event);
        f.grant(1L,11L,"READ","MODULE".equals(restriction)?("MEDICATION_MISSED".equals(event)?"MEASUREMENTS":"MEDICATION"):null,
                "REVOKED".equals(restriction)?"REVOKED":"ACTIVE","EXPIRED".equals(restriction));
        task.tick(); assertSuppressed();
    }

    @ParameterizedTest @ValueSource(strings={"FAMILY_CARE_REMINDER","CARE_FOLLOW_UP","MEDICATION_RESTOCK","MEDICATION_MISSED"})
    void disabledOriginalRecipientIsSuppressedOnEverySendPath(String event) {
        prepare(event);f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=?",target(event)); task.tick();assertSuppressed();
    }

    @ParameterizedTest @ValueSource(strings={"FAMILY_CARE_REMINDER","CARE_FOLLOW_UP","MEDICATION_RESTOCK","MEDICATION_MISSED"})
    void deletedPatientIsSuppressedOnEverySendPath(String event) {
        prepare(event);f.jdbc.update("UPDATE patient SET deleted=1 WHERE id=1");task.tick();assertSuppressed();
    }

    @ParameterizedTest @ValueSource(strings={"FAMILY_CARE_REMINDER","CARE_FOLLOW_UP","MEDICATION_RESTOCK","MEDICATION_MISSED"})
    void validNotificationKeepsOnlyOriginalTargetAndMarksSuccess(String event) {
        prepare(event);task.tick();verify(delivery).notifyUser(eq(target(event)),eq(event),anyString(),anyString());verifyNoMoreInteractions(delivery);
        if("MEDICATION_RESTOCK".equals(event))verify(taskJdbc).update(startsWith("UPDATE medication_stock"),any(),eq(51L));
        else if("MEDICATION_MISSED".equals(event))verify(items).insert(any()); else verify(items).update(isNull(),any());
    }

    @ParameterizedTest @ValueSource(strings={"FAMILY_CARE_REMINDER","CARE_FOLLOW_UP","MEDICATION_RESTOCK","MEDICATION_MISSED"})
    void transportFailureNeverCreatesSuccessfulNotificationMarkers(String event) {
        prepare(event);when(delivery.notifyUser(anyLong(),anyString(),anyString(),anyString())).thenReturn(false);task.tick();
        verify(delivery).notifyUser(eq(target(event)),eq(event),anyString(),anyString());assertNoMarks();
    }

    @ParameterizedTest @ValueSource(strings={"CARE_FOLLOW_UP","MEDICATION_MISSED"})
    void currentAudienceDoesNotReplaceExistingCoordinatorAssignmentCheck(String event) {
        prepare(event);when(membership.canAssign(1L,11L)).thenReturn(false);task.tick();assertSuppressed();
    }

    @Test void medicationOnlyCurrentReadGrantCanReceiveMissedMedicationButNotGenericCare() {
        prepare("MEDICATION_MISSED");f.grant(1L,11L,"READ","MEDICATION","ACTIVE",false);task.tick();
        verify(delivery).notifyUser(eq(11L),eq("MEDICATION_MISSED"),anyString(),anyString());verify(items).insert(any());
    }

    @Test void revokedFamilyGrantDoesNotRemovePatientOwnersIndependentRestockAuthority() {
        prepare("MEDICATION_RESTOCK");f.member(1L,7L);f.grant(1L,7L,"READ",null,"REVOKED",false);task.tick();
        verify(delivery).notifyUser(eq(7L),eq("MEDICATION_RESTOCK"),anyString(),anyString());
    }

    private void prepare(String event) {
        LocalDateTime now=LocalDateTime.now();Patient p=f.patients.selectById(1L);
        if(event.equals("FAMILY_CARE_REMINDER")||event.equals("CARE_FOLLOW_UP")) {
            CareItem item=new CareItem();item.setId(31L);item.setPatientId(1L);item.setKind("APPOINTMENT");item.setStatus("OPEN");item.setAssignedUserId(11L);
            item.setTitle("Original instructions 原文");item.setDetails(Collections.singletonMap("note","Original clinical note 原文"));
            item.setEventAt(event.equals("CARE_FOLLOW_UP")?now.minusHours(2):now.plusHours(2));item.setNotifyAt(event.equals("FAMILY_CARE_REMINDER")?now.minusMinutes(2):null);
            when(items.selectList(any())).thenReturn(Collections.singletonList(item));
        } else {
            when(f.patients.selectList(any())).thenReturn(Collections.singletonList(p));
            if(event.equals("MEDICATION_RESTOCK")) {
                Map<String,Object> low=new HashMap<>();low.put("id",51L);low.put("low",true);low.put("isActive",true);low.put("drugName","Original medication 原文");low.put("quantity",2);low.put("unit","tablets");
                when(stock.snapshot(1L)).thenReturn(Collections.singletonList(low));when(care.coordinator(1L)).thenReturn(null);
            } else {
                Map<String,Object> intake=new HashMap<>();intake.put("id",61L);intake.put("scheduled_at",now.minusHours(1));intake.put("drug_name","Original medication 原文");
                when(taskJdbc.queryForList(anyString(),eq(1L),any(),any())).thenReturn(Collections.singletonList(intake));
            }
        }
    }
    private Long target(String event) { return event.equals("MEDICATION_RESTOCK")?7L:11L; }
    private void assertSuppressed() { verifyNoInteractions(delivery); assertNoMarks(); }
    private void assertNoMarks() { verify(items,never()).update(isNull(),any());verify(items,never()).insert(any());verify(taskJdbc,never()).update(anyString(),any(),any()); }
}
