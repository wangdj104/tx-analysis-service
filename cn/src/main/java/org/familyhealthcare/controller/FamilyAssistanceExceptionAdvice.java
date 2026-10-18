package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.lang.annotation.*;
import java.util.*;
/** Domain-scoped, nonclinical errors; legacy controller semantics are unchanged. */
@RestControllerAdvice(annotations=FamilyAssistanceExceptionAdvice.Api.class) @Order(Ordered.HIGHEST_PRECEDENCE) public class FamilyAssistanceExceptionAdvice {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Inherited public @interface Api {
    }
    @ExceptionHandler(WorkflowException.class) public ResponseEntity<Result<Map<String,Object>>> workflow(WorkflowException failure) {
        return error(failure.getStatus(),failure.getErrorCode(),failure.getMessage());
    }
    @ExceptionHandler( {
        IllegalArgumentException.class,HttpMessageNotReadableException.class
    }
    ) public ResponseEntity<Result<Map<String,Object>>> invalid(Exception failure) {
        return error(400,"INVALID_REQUEST","Invalid assistance request.");
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<Result<Map<String,Object>>> unavailable(Exception failure) {
        return error(503,"STORAGE_INDETERMINATE","Assistance storage could not be verified. Refresh and retry; no completed command is confirmed.");
    }
    private ResponseEntity<Result<Map<String,Object>>> error(int status,String code,String message) {
        Result<Map<String,Object>> result=Result.error(status,message);
        result.setData(Collections.singletonMap("errorCode",code));
        return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);
    }
}
