package org.familyhealthcare.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blood Pressure Pattern Analysis - Pearsonrelativerelationshipcountcalculatesingleelementtest
 */
class BpPatternAnalysisServiceTest {

    private BpPatternAnalysisServiceImpl service;
    private Method pearsonMethod;

    @BeforeEach
    void setUp() throws Exception {
        service = new BpPatternAnalysisServiceImpl();
        pearsonMethod = BpPatternAnalysisServiceImpl.class.getDeclaredMethod(
                "calculatePearsonCorrelation", List.class, List.class);
        pearsonMethod.setAccessible(true);
    }

    private double invokePearson(List<BigDecimal> x, List<Integer> y) throws Exception {
        return (double) pearsonMethod.invoke(service, x, y);
    }

    @Test
    @DisplayName("Perfect positiverelated - relativerelationshipcountapproaches recent 1")
    void perfectPositiveCorrelation() throws Exception {
        List<BigDecimal> x = Arrays.asList(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"));
        List<Integer> y = Arrays.asList(10, 20, 30);
        assertEquals(1.0, invokePearson(x, y), 0.001);
    }

    @Test
    @DisplayName("Perfect negativerelated - relativerelationshipcountapproaches recent -1")
    void perfectNegativeCorrelation() throws Exception {
        List<BigDecimal> x = Arrays.asList(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"));
        List<Integer> y = Arrays.asList(30, 20, 10);
        assertEquals(-1.0, invokePearson(x, y), 0.001);
    }

    @Test
    @DisplayName("singledatapoint - Back0")
    void singlePoint_returns0() throws Exception {
        List<BigDecimal> x = Collections.singletonList(new BigDecimal("1"));
        List<Integer> y = Collections.singletonList(10);
        assertEquals(0.0, invokePearson(x, y), 0.001);
    }

    @Test
    @DisplayName("oftenamountordercolumn - Back0")
    void constantSequence_returns0() throws Exception {
        List<BigDecimal> x = Arrays.asList(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
        List<Integer> y = Arrays.asList(10, 10, 10);
        assertEquals(0.0, invokePearson(x, y), 0.001);
    }

    @Test
    @DisplayName("xoftenamountychange - Back0")
    void xConstant_yVarying_returns0() throws Exception {
        List<BigDecimal> x = Arrays.asList(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
        List<Integer> y = Arrays.asList(10, 20, 30);
        assertEquals(0.0, invokePearson(x, y), 0.001);
    }

    @Test
    @DisplayName("inetc.positiverelated - relativerelationshipcountbetween in0.3-0.9")
    void moderatePositiveCorrelation() throws Exception {
        List<BigDecimal> x = Arrays.asList(
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("4"), new BigDecimal("5"));
        List<Integer> y = Arrays.asList(2, 4, 5, 4, 5);
        double result = invokePearson(x, y);
        assertTrue(result > 0.3 && result < 0.9);
    }
}
