package org.familyhealthcare.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for privacy masking utilities.
 */
class PrivacyMaskUtilTest {

    @Test
    @DisplayName("Phone masking keeps the first 3 and last 4 digits")
    void maskPhone_normal() {
        assertEquals("138****5678", PrivacyMaskUtil.maskPhone("13812345678"));
    }

    @Test
    @DisplayName("Phone masking preserves null")
    void maskPhone_null() {
        assertNull(PrivacyMaskUtil.maskPhone(null));
    }

    @Test
    @DisplayName("Phone masking preserves short values")
    void maskPhone_short() {
        assertEquals("1234", PrivacyMaskUtil.maskPhone("1234"));
    }

    @Test
    @DisplayName("ID masking keeps the first 3 and last 4 characters")
    void maskIdCard_normal() {
        assertEquals("320***********1234", PrivacyMaskUtil.maskIdCard("3201234567891234"));
    }

    @Test
    @DisplayName("ID masking preserves null")
    void maskIdCard_null() {
        assertNull(PrivacyMaskUtil.maskIdCard(null));
    }

    @Test
    @DisplayName("Name masking keeps the first character of a two-character name")
    void maskName_twoChar() {
        assertEquals("L*", PrivacyMaskUtil.maskName("Li"));
    }

    @Test
    @DisplayName("Name masking keeps the first and last characters")
    void maskName_threeChar() {
        assertEquals("L*e", PrivacyMaskUtil.maskName("Lee"));
    }

    @Test
    @DisplayName("Name masking preserves a one-character name")
    void maskName_oneChar() {
        assertEquals("L", PrivacyMaskUtil.maskName("L"));
    }

    @Test
    @DisplayName("Address masking keeps the first 6 characters")
    void maskAddress_normal() {
        assertEquals("Nanjin***", PrivacyMaskUtil.maskAddress("Nanjing Gulou Road 123"));
    }

    @Test
    @DisplayName("Address masking preserves a short address")
    void maskAddress_short() {
        assertEquals("Paris", PrivacyMaskUtil.maskAddress("Paris"));
    }

    @Test
    @DisplayName("Level 0 does not mask")
    void maskByLevel_level0() {
        assertEquals("Lee", PrivacyMaskUtil.maskByLevel("Lee", "name", 0));
    }

    @Test
    @DisplayName("Level 1 masks names")
    void maskByLevel_level1_name() {
        assertEquals("L*e", PrivacyMaskUtil.maskByLevel("Lee", "name", 1));
    }

    @Test
    @DisplayName("Level 1 does not mask IDs")
    void maskByLevel_level1_idCard() {
        assertEquals("3201234567891234", PrivacyMaskUtil.maskByLevel("3201234567891234", "idCard", 1));
    }

    @Test
    @DisplayName("Level 2 masks IDs")
    void maskByLevel_level2_idCard() {
        assertEquals("320***********1234", PrivacyMaskUtil.maskByLevel("3201234567891234", "idCard", 2));
    }

    @Test
    @DisplayName("Level 2 does not mask addresses")
    void maskByLevel_level2_address() {
        assertEquals("Nanjing", PrivacyMaskUtil.maskByLevel("Nanjing", "address", 2));
    }

    @Test
    @DisplayName("Level 3 masks addresses")
    void maskByLevel_level3_address() {
        assertEquals("Nanjin***", PrivacyMaskUtil.maskByLevel("Nanjing Gulou Road 123", "address", 3));
    }
}
