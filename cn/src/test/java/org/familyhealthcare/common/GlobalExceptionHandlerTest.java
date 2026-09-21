package org.familyhealthcare.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test void permissionErrorsBecome403() {
        Result<String> result = new GlobalExceptionHandler().handleState(new IllegalStateException("Access denied"));
        assertEquals(403, result.getCode());
    }

    @Test void businessErrorsBecome400() {
        Result<String> result = new GlobalExceptionHandler().handleState(new IllegalStateException("Patient reference is missing"));
        assertEquals(400, result.getCode());
    }

    @Test void invalidArgumentsBecome400() {
        Result<String> result = new GlobalExceptionHandler().handleArgument(new IllegalArgumentException("A record already exists for this date"));
        assertEquals(400, result.getCode());
        assertEquals("A record already exists for this date", result.getMsg());
    }
}
