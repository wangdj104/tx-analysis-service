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
        return new CarePlanException(403, "ACCESS_DENIED", "您无权访问该患者或执行此操作。");
    }
    public static CarePlanException invalid(String message) {
        return new CarePlanException(400, "INVALID_REQUEST", message);
    }
}
