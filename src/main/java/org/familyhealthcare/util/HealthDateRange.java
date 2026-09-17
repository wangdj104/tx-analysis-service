package org.familyhealthcare.util;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** Shared inclusive calendar range; weeks run Monday through Sunday. */
public final class HealthDateRange {
    public final LocalDate from;
    public final LocalDate to;

    private HealthDateRange(LocalDate from, LocalDate to) { this.from = from; this.to = to; }

    public static HealthDateRange of(String type, String value) {
        if (value == null || value.trim().isEmpty()) return new HealthDateRange(LocalDate.MIN, LocalDate.MAX);
        try {
            String v = value.trim();
            if ("year".equalsIgnoreCase(type)) {
                LocalDate start = Year.parse(v.substring(0, 4)).atDay(1);
                return new HealthDateRange(start, start.plusYears(1).minusDays(1));
            }
            if ("week".equalsIgnoreCase(type)) {
                LocalDate day = v.length() == 7 ? YearMonth.parse(v).atDay(1) : LocalDate.parse(v);
                LocalDate start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return new HealthDateRange(start, start.plusDays(6));
            }
            YearMonth month = YearMonth.parse(v.substring(0, 7));
            return new HealthDateRange(month.atDay(1), month.atEndOfMonth());
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid date range"); }
    }

    public boolean contains(LocalDate date) { return date != null && !date.isBefore(from) && !date.isAfter(to); }
}
