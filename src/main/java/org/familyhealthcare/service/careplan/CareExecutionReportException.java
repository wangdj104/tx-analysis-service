package org.familyhealthcare.service.careplan;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stable report errors. Messages and optional limit details never contain source data. */
public final class CareExecutionReportException extends CarePlanException {
    public enum Code {
        INVALID_REQUEST(400, "The report request is invalid."),
        ACCESS_DENIED(403, "You do not have access to this patient or report."),
        FEATURE_DISABLED(404, "Care-plan reporting is disabled."),
        REPORT_ACCESS_CHANGED(409, "Report access changed. Generate the report again."),
        REPORT_LIMIT_EXCEEDED(422, "The report exceeds a resource limit. Select a smaller scope or format."),
        REPORT_DATA_INCONSISTENT(500, "The report data is inconsistent."),
        REPORT_RENDER_UNAVAILABLE(503, "The report cannot be rendered. Try HTML instead."),
        REPORT_TIMEOUT(503, "Report generation timed out. Select a smaller scope or format.");

        private final int status;
        private final String message;
        Code(int status, String message) { this.status = status; this.message = message; }
    }

    private static final Set<String> LIMIT_KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CURRENT_ACTIONS", "PERIOD_EVENTS", "QUESTIONS", "SOURCE_TEXT_BYTES", "OUTPUT_BYTES")));
    private final String limitKind;
    private final Long limit;

    public CareExecutionReportException(Code code) { this(code, null, null); }
    private CareExecutionReportException(Code code, String limitKind, Long limit) {
        super(code.status, code.name(), code.message);
        if (code == Code.REPORT_LIMIT_EXCEEDED && (limitKind == null || limit == null)) {
            throw new IllegalArgumentException("A report limit error requires limit details.");
        }
        this.limitKind = limitKind;
        this.limit = limit;
    }
    public String getLimitKind() { return limitKind; }
    public Long getLimit() { return limit; }
    public static CareExecutionReportException invalid() { return new CareExecutionReportException(Code.INVALID_REQUEST); }
    public static CareExecutionReportException denied() { return new CareExecutionReportException(Code.ACCESS_DENIED); }
    public static CareExecutionReportException timeout() { return new CareExecutionReportException(Code.REPORT_TIMEOUT); }
    public static CareExecutionReportException limitExceeded(String kind, long limit) {
        if (!LIMIT_KINDS.contains(kind) || limit < 0) throw new IllegalArgumentException("Invalid report limit.");
        return new CareExecutionReportException(Code.REPORT_LIMIT_EXCEEDED, kind, limit);
    }
}
