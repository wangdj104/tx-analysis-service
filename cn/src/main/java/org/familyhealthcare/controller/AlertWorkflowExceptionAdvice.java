package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.lang.annotation.*;
import java.util.*;

/** Opt-in error behavior leaves legacy controllers unchanged. */
@RestControllerAdvice(annotations=AlertWorkflowExceptionAdvice.Api.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AlertWorkflowExceptionAdvice {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Inherited public @interface Api {}
    @ExceptionHandler(AlertWorkflowException.class) public ResponseEntity<Result<Map<String,Object>>> workflow(AlertWorkflowException failure){return error(failure.getStatus()==503?404:failure.getStatus(),failure.getErrorCode(),failure.getMessage());}
    @ExceptionHandler(WorkflowException.class) public ResponseEntity<Result<Map<String,Object>>> shared(WorkflowException failure){return error(failure.getStatus(),failure.getErrorCode(),failure.getMessage());}
    @ExceptionHandler({BindException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class}) public ResponseEntity<Result<Map<String,Object>>> invalid(Exception failure){return error(400,"INVALID_REQUEST","Invalid workflow request.");}
    private ResponseEntity<Result<Map<String,Object>>> error(int status,String code,String message){Result<Map<String,Object>> result=Result.error(status,message);result.setData(Collections.singletonMap("errorCode",code));return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);}
}
