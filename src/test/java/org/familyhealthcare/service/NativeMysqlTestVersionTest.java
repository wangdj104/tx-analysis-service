package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Version identity guard tests only; these are not native server evidence. */
class NativeMysqlTestVersionTest {
    @Test void displayWidthNormalizationDoesNotHideTypeDifferences() {
        assertEquals("bigint",NativeMysqlTestVersion.normalizeIntegerDisplayWidth("bigint(20)"));
        assertEquals("int unsigned",NativeMysqlTestVersion.normalizeIntegerDisplayWidth("int(10) unsigned"));
        assertEquals("tinyint",NativeMysqlTestVersion.normalizeIntegerDisplayWidth("tinyint(4)"));
        assertEquals("varchar(64)",NativeMysqlTestVersion.normalizeIntegerDisplayWidth("varchar(64)"));
        assertEquals("decimal(5,4)",NativeMysqlTestVersion.normalizeIntegerDisplayWidth("decimal(5,4)"));
    }
    @Test void acceptsOnlyTheExplicitlySelectedNativeVersion() {
        assertTrue(NativeMysqlTestVersion.matches("5.7.18", "5.7.18"));
        assertTrue(NativeMysqlTestVersion.matches("5.7.18", "5.7.18-log"));
        assertFalse(NativeMysqlTestVersion.matches("5.7.18", "5.7.180"));
        assertFalse(NativeMysqlTestVersion.matches("5.7.18", "5.7.44"));
        assertFalse(NativeMysqlTestVersion.matches("5.7.18", "8.0.44"));
        assertTrue(NativeMysqlTestVersion.matches("8.0", "8.0.44"));
        assertFalse(NativeMysqlTestVersion.matches("8.0", "5.7.18"));
        assertFalse(NativeMysqlTestVersion.matches("8.0", "8.0.attacker"));
        assertFalse(NativeMysqlTestVersion.matches("5.7", "5.7.18"));
        assertFalse(NativeMysqlTestVersion.matches(null, "5.7.18"));
        assertFalse(NativeMysqlTestVersion.matches("5.7.18", null));
    }
}
