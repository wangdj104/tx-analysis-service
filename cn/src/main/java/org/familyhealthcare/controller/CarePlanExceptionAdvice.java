package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.careplan.CarePlanException;
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

    @ExceptionHandler(CarePlanException.class)
    public ResponseEntity<Result<Map<String,String>>> carePlan(CarePlanException exception) {
        int status=exception.getStatus()==503&&"FEATURE_DISABLED".equals(exception.getErrorCode())?404:exception.getStatus();
        String message=status==400?"请求参数无效，请检查字段、编号及带时区偏移的时间。":status==403?CarePlanException.denied().getMessage():status==409?"照护计划或通知状态已变更，请刷新后重试。":status==404?"照护计划协作未启用。":exception.getMessage();
        return error(status, exception.getErrorCode(), message);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Map<String,String>>> invalid(IllegalArgumentException exception) {
        return error(400, "INVALID_REQUEST", "请求参数无效，请检查字段、编号及带时区偏移的时间。");
    }
    @ExceptionHandler({BindException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Map<String,String>>> malformed(Exception exception) {
        return error(400, "INVALID_REQUEST", "必填参数缺失或格式无效。");
    }
    private ResponseEntity<Result<Map<String,String>>> error(int status, String code, String message) {
        Result<Map<String,String>> result = Result.error(status, message);
        result.setData(Collections.singletonMap("errorCode", code));
        return ResponseEntity.status(status).body(result);
    }
}
