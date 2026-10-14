package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MeasurementAssessmentServiceTest {
    private AlertWorkflowTestDatabase db;private MeasurementAssessmentService service;
    @BeforeEach void setup(){db=new AlertWorkflowTestDatabase();service=new MeasurementAssessmentService(db.jdbc);}
    @Test void journeyGlucoseEightRetainsItsGenericPolicyWhileFastingSelfMonitorIsAbnormal() {
        Map<String,Object> journey=journey("GLUCOSE","8.0",null,"mmol/L");Map<String,Object> self=self(glucose("8.0","mmol/L","Fasting"),null).get(0);
        assertEquals("NORMAL",journey.get("status"));assertEquals("JOURNEY_GLUCOSE",journey.get("policyKey"));
        assertEquals("ABNORMAL",self.get("status"));assertEquals("WARNING",self.get("severity"));assertEquals("SELF_MONITOR_GLUCOSE_FASTING",self.get("policyKey"));
        assertEquals(new BigDecimal("11.1"),((Map<?,?>)journey.get("thresholds")).get("max"));assertEquals(new BigDecimal("6.1"),((Map<?,?>)self.get("thresholds")).get("max"));
    }
    @ParameterizedTest @CsvSource({"Fasting,6.2,ABNORMAL","After Meal2h,7.7,NORMAL","餐后2小时,7.7,NORMAL","Random,6.2,ABNORMAL"})
    void stableAndChinesePeriodsMapToTheirExistingPolicy(String period,String value,String want){Map<String,Object> row=self(glucose(value,"mmol/L",period),null).get(0);assertEquals(want,row.get("status"));if("Random".equals(period))assertEquals("SELF_MONITOR_GLUCOSE_RANDOM_LEGACY",row.get("policyKey"));}
    @Test void targetsOverrideSupportedSelfMonitorRangesWithoutChangingRawSource() {
        PatientHealthTarget target=new PatientHealthTarget();target.setFastingGlucoseMax(new BigDecimal("9.0"));target.setSystolicMax(155);
        BpSelfMonitorRecord record=glucose("8.0","mmol/L","Fasting");record.setSystolicBp(150);record.setDiastolicBp(80);
        List<Map<String,Object>> result=self(record,target);assertEquals(2,result.size());for(Map<String,Object> row:result)assertEquals("NORMAL",row.get("status"));assertEquals(new BigDecimal("8.0"),record.getBloodGlucose());assertEquals(150,record.getSystolicBp());
    }
    @Test void equivalentGlucoseUnitsHaveEqualNormalizedComparisonAndClassification() {
        Map<String,Object> mmol=self(glucose("8.0","mmol/L","Fasting"),null).get(0),mg=self(glucose("144","mg/dL","Fasting"),null).get(0);
        assertEquals(0,((BigDecimal)mmol.get("comparisonValue")).compareTo((BigDecimal)mg.get("comparisonValue")));assertEquals(mmol.get("status"),mg.get("status"));assertEquals("mg/dL",mg.get("sourceUnit"));
        assertEquals("NORMAL",journey("GLUCOSE","144",null,"mg/dL").get("status"));
    }
    @Test void missingOrUnknownRequiredContextIsVisiblyUnclassifiable() {
        for(String period:Arrays.asList(null,"","unexpected"))assertEquals("UNCLASSIFIABLE",self(glucose("8.0","mmol/L",period),null).get(0).get("status"));
        for(String unit:Arrays.asList(null,"","mg/L"))assertEquals("UNCLASSIFIABLE",self(glucose("5.0",unit,"Fasting"),null).get(0).get("status"));
        assertEquals("UNCLASSIFIABLE",journey("GLUCOSE","5.0",null,"mg/L").get("status"));assertEquals("UNCLASSIFIABLE",journey("WEIGHT","65",null,"kg").get("status"));assertEquals("UNCLASSIFIABLE",journey("CUSTOM","1",null,"x").get("status"));
    }
    @Test void unclassifiableContextNeverPretendsAnUnknownUnitHasANormalizedComparison() {
        assertNull(journey("GLUCOSE","144",null,"mg/L").get("comparisonValue"));
    }
    @Test void knownUnitStillNormalizesWhenTheRequiredPeriodIsUnknown() {
        Map<String,Object> unknown=self(glucose("144","mg/dL","unexpected"),null).get(0);
        assertEquals(0,new BigDecimal("8").compareTo((BigDecimal)unknown.get("comparisonValue")));
    }
    @Test void unknownPeriodIsRetainedAsContextForAnExplicitCorrection() {
        assertEquals("unexpected",self(glucose("144","mg/dL","unexpected"),null).get(0).get("period"));
    }
    @Test void criticalBoundariesAndPatientTargetsRemainSourceSpecific() {
        assertEquals("CRITICAL",self(glucose("16.8","mmol/L","Random"),null).get(0).get("severity"));assertEquals("WARNING",self(glucose("16.7","mmol/L","Random"),null).get(0).get("severity"));
        BpSelfMonitorRecord row=new BpSelfMonitorRecord();row.setSystolicBp(180);row.setDiastolicBp(80);assertEquals("CRITICAL",self(row,null).get(0).get("severity"));
        db.jdbc.update("INSERT INTO patient_health_target(id,patient_id,systolic_max,target_weight,weight_gain_limit) VALUES(1,10,160,65,2)");assertEquals("NORMAL",journey("BP","150","80","mmHg").get("status"));assertEquals("ABNORMAL",journey("WEIGHT","68",null,"kg").get("status"));
    }
    @Test void partialBloodPressureIsUnclassifiableInWorkflowAndKeepsLegacyMonitorResult() {
        BpSelfMonitorRecord row=new BpSelfMonitorRecord();row.setSystolicBp(120);assertEquals("UNCLASSIFIABLE",self(row,null).get(0).get("status"));assertEquals("NORMAL",service.legacyBpSeverity(row,null));
    }
    @ParameterizedTest @CsvSource({"BP,90,60,mmHg,NORMAL","BP,141,80,mmHg,ABNORMAL","SPO2,92,,%,NORMAL","SPO2,91,,%,ABNORMAL","HEART_RATE,50,,bpm,NORMAL","HEART_RATE,121,,bpm,ABNORMAL","TEMPERATURE,38,,°C,NORMAL","TEMPERATURE,38.1,,°C,ABNORMAL"})
    void knownJourneyInputsRetainPriorRangeResults(String metric,String primary,String secondary,String unit,String want){assertEquals(want,journey(metric,primary,secondary,unit).get("status"));}
    @Test void legacyAdaptersKeepPreviouslyAcceptedBlankGlucoseContextAndRandomBranch() {
        BpSelfMonitorRecord record=glucose("8.0",null,null);assertEquals("WARNING",service.legacyGlucoseSeverity(record,null));
        assertEquals("NORMAL",service.legacyJourneyStatus(10L,"CUSTOM",new BigDecimal("1"),null));
    }
    private BpSelfMonitorRecord glucose(String value,String unit,String period){BpSelfMonitorRecord row=new BpSelfMonitorRecord();row.setPatientId(10L);row.setBloodGlucose(new BigDecimal(value));row.setBgUnit(unit);row.setMeasurePeriod(period);return row;}
    @SuppressWarnings("unchecked") private Map<String,Object> journey(String metric,String primary,String secondary,String unit){return(Map<String,Object>)service.assessJourney(10L,metric,new BigDecimal(primary),secondary==null?null:new BigDecimal(secondary),unit);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> self(BpSelfMonitorRecord row,PatientHealthTarget target){return(List<Map<String,Object>>)service.assessSelfMonitor(row,target);}
}
