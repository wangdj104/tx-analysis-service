package org.familyhealthcare.service;
import org.familyhealthcare.controller.*;
import org.familyhealthcare.common.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class CareExecutionSourceHttpTest {
 MockMvc mvc; CareJourneyService journey; MedicalRecordService medical;
 @BeforeEach void setup(){journey=mock(CareJourneyService.class);medical=mock(MedicalRecordService.class);CareJourneyController jc=new CareJourneyController();ReflectionTestUtils.setField(jc,"service",journey);MedicalRecordController mc=new MedicalRecordController();ReflectionTestUtils.setField(mc,"medicalRecordService",medical);mvc=MockMvcBuilders.standaloneSetup(jc,mc).setControllerAdvice(new GlobalExceptionHandler()).build();}
 @Test void focusedSelectorDispatchesExactPatientAndSource()throws Exception{mvc.perform(get("/care-journey/measurements").param("patientId","1").param("measurementId","17")).andExpect(status().isOk());verify(journey).measurements(1L,null,null,null,17L);mvc.perform(get("/medical-record/17").param("patientId","1")).andExpect(status().isOk());verify(medical).getRecordWithDetails(17L,1L);}
 @Test void focusedSelectorsRejectDuplicatesExtrasAndInvalidIdsBeforeService()throws Exception{
  for(String endpoint:new String[]{"/care-journey/measurements","/medical-record/17"}){
   String selector=endpoint.contains("measurements")?"measurementId":"patientId";
   for(String value:new String[]{"0","-1","01","1e2",""}){
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request=get(endpoint).param(selector,value);if(selector.equals("measurementId"))request.param("patientId","1");
    mvc.perform(request).andExpect(jsonPath("$.code").value(400));
   }
   org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder duplicate=get(endpoint).param("patientId","1","1");if(selector.equals("measurementId"))duplicate.param("measurementId","17");mvc.perform(duplicate).andExpect(jsonPath("$.code").value(400));
   org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder extra=get(endpoint).param("patientId","1").param("redirect","elsewhere");if(selector.equals("measurementId"))extra.param("measurementId","17");mvc.perform(extra).andExpect(jsonPath("$.code").value(400));
  }verifyNoInteractions(journey,medical);
 }
 @Test void ordinaryReadsKeepExistingSemantics()throws Exception{mvc.perform(get("/care-journey/measurements").param("patientId","1").param("metricType","WEIGHT"));verify(journey).measurements(1L,"WEIGHT",null,null);mvc.perform(get("/medical-record/17"));verify(medical).getRecordWithDetails(17L);}
}
