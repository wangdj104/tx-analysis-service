package org.familyhealthcare.service;
import static org.familyhealthcare.util.ExportLocalization.text;
/** An observed run or claim no longer matches; callers must refresh, never retry blindly. */
public final class HealthAnalysisRunConflictException extends IllegalStateException {
    public HealthAnalysisRunConflictException() {super(text("The run state changed or requires confirmation. Refresh and confirm the current run.","运行状态已改变或需要确认，请刷新后确认当前运行。"));}
}
