package org.familyhealthcare.service.workflow;

/** Technical command errors; each domain adapter retains its own API error contract. */
public final class WorkflowException extends RuntimeException {
    private final int status;
    private final String errorCode;

    public WorkflowException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public static WorkflowException invalid(String message) { return new WorkflowException(400, "INVALID_REQUEST", message); }
}
