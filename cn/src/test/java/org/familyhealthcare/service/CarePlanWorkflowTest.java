package org.familyhealthcare.service;
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

class CarePlanWorkflowTest {
    FamilyCareService service;CareItemMapper items;MedicationReminderMapper reminders;MedicationMapper medications;DataScopeHelper scope;
    @BeforeEach void setup(){service=new FamilyCareService();items=mock(CareItemMapper.class);reminders=mock(MedicationReminderMapper.class);medications=mock(MedicationMapper.class);scope=mock(DataScopeHelper.class);ReflectionTestUtils.setField(service,"items",items);ReflectionTestUtils.setField(service,"reminders",reminders);ReflectionTestUtils.setField(service,"medications",medications);ReflectionTestUtils.setField(service,"scope",scope);}
    CareItem order(String action){CareItem v=new CareItem();v.setId(1L);v.setPatientId(2L);v.setUserId(3L);v.setKind("ORDER");v.setStatus("SCHEDULED");v.setEventAt(LocalDate.now().atStartOfDay());Map<String,Object>d=new HashMap<>();d.put("medicationId",4L);d.put("action",action);d.put("repeatDays","1,3,5");d.put("unit","tablet");Map<String,Object>dose=new HashMap<>();dose.put("time","08:00");dose.put("quantity",1);d.put("doses",Collections.singletonList(dose));v.setDetails(d);return v;}
    @Test void futurePlanDoesNotChangeCurrentReminders(){CareItem v=order("CHANGE");v.setEventAt(LocalDate.now().plusDays(1).atStartOfDay());service.activateIfDue(v,LocalDate.now());verifyNoInteractions(reminders,medications);}
    @Test void changeActivatesOnceAndStopDisablesDrug(){CareItem v=order("CHANGE");when(items.selectOne(any())).thenReturn(v);Medication m=new Medication();m.setId(4L);when(medications.selectById(4L)).thenReturn(m);service.activateIfDue(v,LocalDate.now());service.activateIfDue(v,LocalDate.now());assertEquals("ACTIVE",v.getStatus());verify(reminders,times(1)).insert(any());assertEquals(1,m.getIsActive());CareItem stop=order("STOP");when(items.selectOne(any())).thenReturn(stop);service.activateIfDue(stop,LocalDate.now());assertEquals("STOPPED",stop.getStatus());assertEquals(0,m.getIsActive());verify(reminders,times(1)).insert(any());}
    @Test void appointmentCompletionCreatesNextOnlyOnce(){CareMembershipService members=mock(CareMembershipService.class);ReflectionTestUtils.setField(service,"membership",members);when(members.canAssign(any(),any())).thenReturn(true);Patient p=new Patient();p.setUserId(3L);when(scope.requirePatient(2L)).thenReturn(p);when(scope.requireUserId()).thenReturn(3L);CareItem a=new CareItem();a.setId(9L);a.setPatientId(2L);a.setUserId(3L);a.setKind("APPOINTMENT");a.setTitle("follow-up examination");a.setStatus("OPEN");a.setDetails(Collections.singletonMap("nextAt","2026-12-20 09:00:00"));when(items.selectOne(any())).thenReturn(a);doAnswer(call->{CareItem row=call.getArgument(0);row.setId(10L);return 1;}).when(items).insert(any());service.action(9L,"DONE",null);service.action(9L,"DONE",null);verify(items,times(1)).insert(argThat(row->"APPOINTMENT".equals(row.getKind())));assertEquals(10L,((Number)a.getDetails().get("nextAppointmentId")).longValue());}
}
