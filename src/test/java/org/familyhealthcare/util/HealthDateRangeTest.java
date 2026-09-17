package org.familyhealthcare.util;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
class HealthDateRangeTest {
    @Test void weekSpansCalendarYearBoundary() {
        HealthDateRange range=HealthDateRange.of("week","2026-01-01");
        assertEquals(LocalDate.of(2025,12,29),range.from);assertEquals(LocalDate.of(2026,1,4),range.to);
        assertTrue(range.contains(LocalDate.of(2025,12,31)));assertFalse(range.contains(LocalDate.of(2026,1,5)));
    }
    @Test void leapMonthAndYearAreInclusive() {
        assertTrue(HealthDateRange.of("month","2024-02").contains(LocalDate.of(2024,2,29)));
        assertFalse(HealthDateRange.of("month","2024-02").contains(LocalDate.of(2024,3,1)));
        assertTrue(HealthDateRange.of("year","2026").contains(LocalDate.of(2026,12,31)));
    }
}
