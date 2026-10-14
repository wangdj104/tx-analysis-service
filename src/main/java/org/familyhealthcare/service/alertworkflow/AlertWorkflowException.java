package org.familyhealthcare.service.alertworkflow;

/** Stable errors deliberately reveal no patient/source existence to an unauthorized actor. */
public class AlertWorkflowException extends RuntimeException {
    private final int status;
    private final String errorCode;
    public AlertWorkflowException(int status,String errorCode,String message){super(message);this.status=status;this.errorCode=errorCode;}
    public int getStatus(){return status;}
    public String getErrorCode(){return errorCode;}
    public static AlertWorkflowException denied(){return new AlertWorkflowException(403,"ACCESS_DENIED","You do not have access to this patient or action.");}
    public static AlertWorkflowException invalid(String message){return new AlertWorkflowException(400,"INVALID_REQUEST",message);}
    public static AlertWorkflowException conflict(){return new AlertWorkflowException(409,"VERSION_CONFLICT","The follow-up changed. Refresh before trying again.");}
}
