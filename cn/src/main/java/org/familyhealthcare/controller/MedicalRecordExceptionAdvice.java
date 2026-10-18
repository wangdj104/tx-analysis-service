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
    @ExceptionHandler(org.familyhealthcare.service.workflow.WorkflowException.class)
    public ResponseEntity<Result<?>> correctionFailure(org.familyhealthcare.service.workflow.WorkflowException failure) {
        return MedicalRecordCorrectionController.failure(failure);
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<?>> storageFailure(org.springframework.dao.DataAccessException failure) {
        return MedicalRecordCorrectionController.failure(new org.familyhealthcare.service.workflow.WorkflowException(503,
                "MEDICAL_RECORD_UNAVAILABLE", "暂时无法完成病历操作，请重新读取后重试。"));
    }
    @ExceptionHandler(MedicalRecordReviewContract.Conflict.class)
    public ResponseEntity<Result<String>> conflict(MedicalRecordReviewContract.Conflict exception) {
        return ResponseEntity.status(409).header("Cache-Control", "no-store, private").header("X-Content-Type-Options", "nosniff").body(Result.error(409, exception.getMessage()));
    }
}
