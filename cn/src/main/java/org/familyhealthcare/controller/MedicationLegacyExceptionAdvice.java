package org.familyhealthcare.controller;
import org.familyhealthcare.common.Result;import org.familyhealthcare.service.workflow.WorkflowException;import org.springframework.core.Ordered;import org.springframework.core.annotation.Order;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;import java.util.*;
/** Typed next actions for protected native routes, without changing their other legacy exception semantics. */
@RestControllerAdvice(assignableTypes={FamilyHealthController.class,CareController.class,MedicationController.class,MedicationReminderController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MedicationLegacyExceptionAdvice {
 @ExceptionHandler(WorkflowException.class) public ResponseEntity<Result<Map<String,Object>>> workflow(WorkflowException failure){Result<Map<String,Object>> result=Result.error(failure.getStatus(),failure.getMessage());result.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));return ResponseEntity.status(failure.getStatus()).header("Cache-Control","no-store, private").body(result);}
}
