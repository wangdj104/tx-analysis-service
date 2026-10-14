package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** The enabled branch shares workflow errors without changing unrelated legacy controller exceptions. */
@RestControllerAdvice(assignableTypes={CareJourneyController.class,BpSelfMonitorRecordController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MeasurementWorkflowExceptionAdvice {
    @ExceptionHandler(AlertWorkflowException.class)
    public ResponseEntity<Result<Map<String,Object>>> workflow(AlertWorkflowException failure){
        int status=failure.getStatus()==503?404:failure.getStatus();Result<Map<String,Object>> result=Result.error(status,failure.getMessage());
        result.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));
        return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(result);
    }
}
