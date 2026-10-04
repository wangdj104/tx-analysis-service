package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.service.careplan.CareExecutionReportException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.annotation.*;
import java.util.Collections;
import java.util.Map;

/** Opt-in marker scope keeps existing controllers' HTTP/error strategy unchanged. */
@RestControllerAdvice(annotations = CarePlanExceptionAdvice.Api.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CarePlanExceptionAdvice {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    public @interface Api { }

    @ExceptionHandler(CareExecutionReportException.class)
    public ResponseEntity<Result<Map<String,Object>>> report(CareExecutionReportException exception) {
        Map<String,Object> details=new java.util.LinkedHashMap<>();details.put("errorCode",exception.getErrorCode());
        if(exception.getLimitKind()!=null){details.put("limitKind",exception.getLimitKind());details.put("limit",exception.getLimit());}
        Result<Map<String,Object>> result=Result.error(exception.getStatus(),exception.getMessage());result.setData(details);
        return ResponseEntity.status(exception.getStatus()).body(result);
    }
    @ExceptionHandler(CarePlanException.class)
    public ResponseEntity<Result<Map<String,String>>> carePlan(CarePlanException exception) {
        int status=exception.getStatus()==503&&"FEATURE_DISABLED".equals(exception.getErrorCode())?404:exception.getStatus();
        return error(status, exception.getErrorCode(), exception.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Map<String,String>>> invalid(IllegalArgumentException exception) {
        return error(400, "INVALID_REQUEST", exception.getMessage());
    }
    @ExceptionHandler({BindException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Map<String,String>>> malformed(Exception exception) {
        return error(400, "INVALID_REQUEST", "A required parameter is missing or has an invalid format.");
    }
    private ResponseEntity<Result<Map<String,String>>> error(int status, String code, String message) {
        Result<Map<String,String>> result = Result.error(status, message);
        result.setData(Collections.singletonMap("errorCode", code));
        return ResponseEntity.status(status).body(result);
    }
}
