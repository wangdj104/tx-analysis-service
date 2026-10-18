package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.lang.annotation.*;
import java.util.*;

/** Nonclinical errors scoped to the common-goal API; no private body or database detail. */
@RestControllerAdvice(annotations=CommonCareGoalExceptionAdvice.Api.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommonCareGoalExceptionAdvice {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Inherited public @interface Api { }
    @ExceptionHandler(WorkflowException.class) public ResponseEntity<Result<Map<String,Object>>> workflow(WorkflowException failure){return error(failure.getStatus(),failure.getErrorCode(),failure.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,java.io.IOException.class}) public ResponseEntity<Result<Map<String,Object>>> invalid(Exception failure){return error(400,"COMMON_GOAL_INVALID_REQUEST","The common care goal request is invalid.");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Map<String,Object>>> unavailable(Exception failure){return error(503,"COMMON_GOAL_STORAGE_INDETERMINATE","The command result or requested data could not be verified. Check the original receipt or retry the same frozen request; do not use a new command key.");}
    private ResponseEntity<Result<Map<String,Object>>> error(int status,String code,String message){
        Result<Map<String,Object>> result=Result.error(status,message);result.setData(Collections.singletonMap("errorCode",code));
        return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);
    }
}
