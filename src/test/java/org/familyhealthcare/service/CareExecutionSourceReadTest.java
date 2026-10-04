package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.impl.MedicalRecordServiceImpl;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CareExecutionSourceReadTest {
    CarePlanTestFixture f; CareJourneyService journey; MedicalRecordServiceImpl medical;
    DataScopeHelper scope; MedicalRecordMapper records; MedicalRecordItemMapper items; MedicalRecordAttachmentMapper attachments;
    CarePlanProperties properties;
    @BeforeEach void setup() throws Exception {
        f=new CarePlanTestFixture(); scope=mock(DataScopeHelper.class);records=mock(MedicalRecordMapper.class);items=mock(MedicalRecordItemMapper.class);attachments=mock(MedicalRecordAttachmentMapper.class);
        properties=new CarePlanProperties(true,Clock.fixed(f.now(),ZoneOffset.UTC));
        CarePlanAuthorizationService auth=new CarePlanAuthorizationService(f.jdbc(),properties);
        journey=new CareJourneyService();ReflectionTestUtils.setField(journey,"jdbc",f.jdbc());ReflectionTestUtils.setField(journey,"scope",scope);ReflectionTestUtils.setField(journey,"carePlanAuthorization",auth);
        medical=new MedicalRecordServiceImpl();ReflectionTestUtils.setField(medical,"baseMapper",records);ReflectionTestUtils.setField(medical,"itemMapper",items);ReflectionTestUtils.setField(medical,"attachmentMapper",attachments);ReflectionTestUtils.setField(medical,"dataScopeHelper",scope);
        // The optional new collaborator does not exist in the RED baseline.
        if(Arrays.stream(MedicalRecordServiceImpl.class.getDeclaredFields()).anyMatch(field->field.getName().equals("carePlanAuthorization")))ReflectionTestUtils.setField(medical,"carePlanAuthorization",auth);
        f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(1,1,7,'WEIGHT',60,'kg','2020-01-01 00:00:00'),(2,2,8,'WEIGHT',65,'kg','2026-01-01 00:00:00')");
        f.jdbc().update("INSERT INTO medical_record(id,patient_name,patient_id,user_id) VALUES(1,'Synthetic',1,7),(2,'Other synthetic',2,8)");
        when(records.selectOne(any())).thenAnswer(invocation->{QueryWrapper<?> query=invocation.getArgument(0);assertTrue(query.getSqlSegment().contains("patient_id"));assertTrue(query.getSqlSegment().contains("(id = "));assertEquals(2,query.getParamNameValuePairs().size());assertEquals(new HashSet<>(Arrays.asList(1L)),new HashSet<>(query.getParamNameValuePairs().values()));MedicalRecord row=new MedicalRecord();row.setId(1L);row.setPatientId(1L);return row;});
    }
    @AfterEach void close()throws Exception{f.close();}
    Object call(Object target,String name,Class<?>[] types,Object...args){
        Method method=Arrays.stream(target.getClass().getMethods()).filter(m->m.getName().equals(name)&&Arrays.equals(m.getParameterTypes(),types)).findFirst().orElse(null);
        assertNotNull(method,"Exact patient-bound read overload exists");
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    List<?> measurement(long patient,long id){return (List<?>)call(journey,"measurements",new Class<?>[]{Long.class,String.class,LocalDateTime.class,LocalDateTime.class,Long.class},patient,null,null,null,id);}
    MedicalRecord record(long patient,long id){return (MedicalRecord)call(medical,"getRecordWithDetails",new Class<?>[]{Long.class,Long.class},id,patient);}
    void grant(String modules){f.jdbc().update("DELETE FROM care_access_grant WHERE patient_id=1 AND grantee_user_id=8");f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,8,'FAMILY','READ',?,7)",modules);f.as(FAMILY);}
    @Test void carePlanOnlyDeniesBothBeforeAnyBodyItemsOrAttachments(){grant("CARE_PLAN");assertThrows(IllegalStateException.class,()->measurement(1,1));assertThrows(IllegalStateException.class,()->record(1,1));verifyNoInteractions(scope,records,items,attachments);}
    @Test void exactlyOneMeasurementGrantReadsAnOldReferenceBeyondTheUnchanged1000Cap(){grant("CARE_PLAN,MEASUREMENTS");for(int id=3;id<1005;id++)f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(?,1,7,'WEIGHT',60,'kg','2026-10-01 00:00:00')",id);List<?> rows=measurement(1,1);assertEquals(1,rows.size());assertEquals(1L,((Number)((Map<?,?>)rows.get(0)).get("id")).longValue());assertThrows(IllegalStateException.class,()->record(1,1));verifyNoInteractions(scope,records,items,attachments);}
    @Test void exactlyOneMedicalGrantReadsOnlyTheBoundRecordWithoutFullPatient(){grant("CARE_PLAN,MEDICAL");assertEquals(1L,record(1,1).getPatientId());verify(items).selectList(any());verify(attachments).selectList(any());assertThrows(IllegalStateException.class,()->measurement(1,1));verifyNoInteractions(scope);}
    @Test void ownerAndAssignedDoctorReadBothSources(){for(long actor:new long[]{OWNER,DOCTOR}){f.as(actor);assertEquals(1,measurement(1,1).size());assertNotNull(record(1,1));}verifyNoInteractions(scope);}
    @Test void missingCrossPatientRevokedAndAdminCannotReadSources(){grant("CARE_PLAN,MEASUREMENTS,MEDICAL");for(long id:new long[]{2,999}){assertThrows(IllegalStateException.class,()->measurement(1,id));assertThrows(IllegalStateException.class,()->record(1,id));}f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");assertThrows(IllegalStateException.class,()->measurement(1,1));assertThrows(IllegalStateException.class,()->record(1,1));f.as(ADMIN);assertThrows(IllegalStateException.class,()->record(1,1));verifyNoInteractions(scope,records,items,attachments);}
    @Test void disabledOrAbsentCareBeansDenyFocusedReadsButLeaveOrdinaryMeasurementReadsUsable(){ReflectionTestUtils.setField(properties,"enabled",false);assertThrows(IllegalStateException.class,()->measurement(1,1));ReflectionTestUtils.setField(journey,"carePlanAuthorization",null);assertThrows(IllegalStateException.class,()->measurement(1,1));assertEquals(1,journey.measurements(1L,null,null,null).size());verify(scope).requirePatientAccess(1L,"MEASUREMENTS",false);}
    @Test void patientSpecificItemNamesRequireMedicalReadBeforeNamesSql(){doThrow(new IllegalStateException("Access denied")).when(scope).requirePatientAccess(1L,"MEDICAL",false);assertThrows(IllegalStateException.class,()->medical.getAllItemNames(1L));verifyNoInteractions(items);}
    @Test void authorizedItemNamesPreserveExistingQuery(){when(items.selectDistinctItemNamesByPatientId(1L)).thenReturn(Arrays.asList("Synthetic item"));assertEquals(Arrays.asList("Synthetic item"),medical.getAllItemNames(1L));verify(scope).requirePatientAccess(1L,"MEDICAL",false);}
}
