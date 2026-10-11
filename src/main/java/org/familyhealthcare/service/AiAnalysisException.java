package org.familyhealthcare.service;

import static org.familyhealthcare.util.ExportLocalization.text;

/** 失败与临床正文分离；不向用户或草稿泄漏提供方响应、令牌及原始请求。 */
public final class AiAnalysisException extends IllegalStateException {
    public enum Reason { HTTP_FAILURE, TRANSPORT_FAILURE, INVALID_RESPONSE, EMPTY_RESULT }
    private final Reason reason;
    private final boolean outcomeUncertain;
    public AiAnalysisException(Reason reason) { this(reason, reason == Reason.TRANSPORT_FAILURE); }
    public AiAnalysisException(Reason reason, boolean outcomeUncertain) {
        super(text("AI analysis could not be completed. Check the service configuration and try again.",
                "AI 分析调用失败，请检查服务配置后重试。"));
        this.reason=reason; this.outcomeUncertain=outcomeUncertain;
    }
    public boolean isOutcomeUncertain() { return outcomeUncertain; }
    public Reason getReason() { return reason; }
}
