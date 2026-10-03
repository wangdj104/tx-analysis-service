package org.familyhealthcare.service.careplan;

/** A stable, deliberately non-identifying error for the opt-in care-plan API. */
public class CarePlanException extends RuntimeException {
    private final int status;
    private final String errorCode;

    public CarePlanException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public static CarePlanException denied() {
        return new CarePlanException(403, "ACCESS_DENIED", "You do not have access to this patient or action.");
    }
    public static CarePlanException invalid(String message) {
        return new CarePlanException(400, "INVALID_REQUEST", message);
    }
}
