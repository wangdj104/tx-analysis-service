package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.MedicalRecordReviewContract;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Snapshot conflicts have an explicit HTTP status without changing other controllers. */
@RestControllerAdvice(assignableTypes = {MedicalRecordController.class, DoctorWorkspaceController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MedicalRecordExceptionAdvice {
    @ExceptionHandler(MedicalRecordReviewContract.Conflict.class)
    public ResponseEntity<Result<String>> conflict(MedicalRecordReviewContract.Conflict exception) {
        return ResponseEntity.status(409).body(Result.error(409, exception.getMessage()));
    }
}
