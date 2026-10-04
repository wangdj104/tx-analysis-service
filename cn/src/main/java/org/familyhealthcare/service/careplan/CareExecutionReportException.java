package org.familyhealthcare.service.careplan;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stable report errors. Messages and optional limit details never contain source data. */
public final class CareExecutionReportException extends CarePlanException {
    public enum Code {
        INVALID_REQUEST(400, "报告请求无效。"),
        ACCESS_DENIED(403, "您无权访问该患者或报告。"),
        FEATURE_DISABLED(404, "照护计划报告功能未启用。"),
        REPORT_ACCESS_CHANGED(409, "报告权限已变化，请重新生成。"),
        REPORT_LIMIT_EXCEEDED(422, "报告超出资源限制，请缩小范围或选择较小的导出格式。"),
        REPORT_DATA_INCONSISTENT(500, "报告数据不一致，无法生成准确报告。"),
        REPORT_RENDER_UNAVAILABLE(503, "报告无法完整渲染，请尝试HTML格式。"),
        REPORT_TIMEOUT(503, "报告生成超时，请缩小范围或选择较小的导出格式。");

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
