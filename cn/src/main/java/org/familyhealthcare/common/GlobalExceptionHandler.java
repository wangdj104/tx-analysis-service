package org.familyhealthcare.common;

import org.apache.catalina.connector.ClientAbortException;
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
        if (clientDisconnected(e)) return null;
        String message = e.getMessage() == null ? "操作失败" : e.getMessage();
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        int code = normalized.contains("permission denied") || normalized.contains("access denied")
                || normalized.contains("do not have permission") ? 403 : 400;
        return Result.error(code, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleArgument(IllegalArgumentException e) {
        if (clientDisconnected(e)) return null;
        return Result.error(400, e.getMessage() == null ? "请求参数无效。" : e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, HttpMessageNotReadableException.class})
    public Result<String> handleValidation(Exception e) {
        if (clientDisconnected(e)) return null;
        return Result.error(400, "必填参数缺失或格式无效。");
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleUnknown(Exception e) {
        if (clientDisconnected(e)) return null;
        log.error("Unhandled request error", e);
        return Result.error(500, "系统暂时无法处理该请求，请稍后重试。");
    }

    /** A confirmed Tomcat disconnect has no writable response; do not serialize another error body. */
    private boolean clientDisconnected(Throwable failure) {
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ClientAbortException) {
                log.debug("Client disconnected before the response could be completed.");
                return true;
            }
        }
        return false;
    }
}
