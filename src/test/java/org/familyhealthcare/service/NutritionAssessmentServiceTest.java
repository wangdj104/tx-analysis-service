package org.familyhealthcare.service;

import org.familyhealthcare.entity.NutritionAssessment;
import org.familyhealthcare.service.impl.NutritionAssessmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nutrition Assessmentcalculatelogicsingleelementtest
 */
class NutritionAssessmentServiceTest {

    private NutritionAssessmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NutritionAssessmentServiceImpl();
    }

    @Test
    @DisplayName("GoodNutritionStatus: BMINormal, albuminNormal, before albuminNormal, SGAhighpoint")
    void calculateNutritionStatus_good() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("22.5"));
        record.setAlbumin(new BigDecimal("42"));
        record.setPreAlbumin(new BigDecimal("350"));
        record.setSgaScore(7);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("GOOD", result.getNutritionStatus());
        assertEquals("A", result.getSgaGrade());
        assertNotNull(result.getSupplementAdvice());
        assertTrue(result.getSupplementAdvice().contains("good"));
    }

    @Test
    @DisplayName("has RiskStatus: BMIlow, albuminomitlow (totalRiskpoint=2) ")
    void calculateNutritionStatus_atRisk() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("19.0"));  // +1 (18.5-20)
        record.setAlbumin(new BigDecimal("37")); // +1 (35-40)
        record.setSgaScore(5);                   // SGA>4, not addpoint

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("AT_RISK", result.getNutritionStatus());
        assertEquals("B", result.getSgaGrade());
        assertTrue(result.getSupplementAdvice().contains("risk"));
    }

    @Test
    @DisplayName("deficientStatus: BMIpastlow, albuminlow, before albuminlow, SGAlowpoint")
    void calculateNutritionStatus_deficient() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("17.0"));
        record.setAlbumin(new BigDecimal("30"));
        record.setPreAlbumin(new BigDecimal("150"));
        record.setSgaScore(2);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("DEFICIENT", result.getNutritionStatus());
        assertEquals("C", result.getSgaGrade());
        assertTrue(result.getSupplementAdvice().contains("deficient"));
    }

    @Test
    @DisplayName("BMIbordervalue18.5 - exactly goodnot addpoint")
    void calculateNutritionStatus_bmiBoundary18_5() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("18.5"));
        record.setSgaScore(7);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("GOOD", result.getNutritionStatus());
    }

    @Test
    @DisplayName("BMIbordervalue20 - not addpoint")
    void calculateNutritionStatus_bmiBoundary20() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("20.0"));
        record.setSgaScore(7);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("GOOD", result.getNutritionStatus());
    }

    @Test
    @DisplayName("albuminbordervalue35 - not addpoint")
    void calculateNutritionStatus_albuminBoundary35() {
        NutritionAssessment record = new NutritionAssessment();
        record.setAlbumin(new BigDecimal("35"));
        record.setSgaScore(7);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("GOOD", result.getNutritionStatus());
    }

    @Test
    @DisplayName("SGAscore3 - Blevel")
    void calculateNutritionStatus_sgaScore3() {
        NutritionAssessment record = new NutritionAssessment();
        record.setSgaScore(3);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("B", result.getSgaGrade());
    }

    @Test
    @DisplayName("SGAscore6 - Alevel")
    void calculateNutritionStatus_sgaScore6() {
        NutritionAssessment record = new NutritionAssessment();
        record.setSgaScore(6);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("A", result.getSgaGrade());
    }

    @Test
    @DisplayName("SGAscore1 - Clevel, 3Riskpoint=AT_RISK")
    void calculateNutritionStatus_sgaScore1() {
        NutritionAssessment record = new NutritionAssessment();
        record.setSgaScore(1); // +3, total=3 → AT_RISK

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("C", result.getSgaGrade());
        assertEquals("AT_RISK", result.getNutritionStatus());
    }

    @Test
    @DisplayName("deficientStatusadd detailsrecommendationcontainProteinrecommendation")
    void supplementAdvice_containsProteinAdvice() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("17.0"));
        record.setAlbumin(new BigDecimal("30"));
        record.setSgaScore(1);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertTrue(result.getSupplementAdvice().contains("protein"));
    }

    @Test
    @DisplayName("deficientStatusandBMIlow - add detailsrecommendationcontainCaloriesrecommendation")
    void supplementAdvice_containsCalorieAdvice() {
        NutritionAssessment record = new NutritionAssessment();
        record.setBmi(new BigDecimal("17.0"));
        record.setSgaScore(1);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertTrue(result.getSupplementAdvice().contains("calorie"));
    }

    @Test
    @DisplayName("has indicatorfor null - DefaultGOOD")
    void calculateNutritionStatus_allNull() {
        NutritionAssessment record = new NutritionAssessment();

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("GOOD", result.getNutritionStatus());
        assertNotNull(result.getSupplementAdvice());
    }

    @Test
    @DisplayName("onlybefore albuminlow - AT_RISK")
    void calculateNutritionStatus_lowPreAlbuminOnly() {
        NutritionAssessment record = new NutritionAssessment();
        record.setPreAlbumin(new BigDecimal("180"));
        record.setSgaScore(7);

        NutritionAssessment result = service.calculateNutritionStatus(record);

        assertEquals("AT_RISK", result.getNutritionStatus());
    }
}
