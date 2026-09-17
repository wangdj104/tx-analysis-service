package org.familyhealthcare.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public Result<String> handleState(IllegalStateException e) {
        String message = e.getMessage() == null ? "Operation failed" : e.getMessage();
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        int code = normalized.contains("permission denied") || normalized.contains("access denied")
                || normalized.contains("do not have permission") ? 403 : 400;
        return Result.error(code, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleArgument(IllegalArgumentException e) {
        return Result.error(400, e.getMessage() == null ? "Invalid request parameter." : e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, HttpMessageNotReadableException.class})
    public Result<String> handleValidation(Exception e) {
        return Result.error(400, "A required parameter is missing or has an invalid format.");
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleUnknown(Exception e) {
        log.error("Unhandled request error", e);
        return Result.error(500, "The system could not process the request. Please try again later.");
    }
}
