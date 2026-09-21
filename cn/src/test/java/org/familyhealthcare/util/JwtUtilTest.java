package org.familyhealthcare.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWTutility classsingleelementtest
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = new JwtUtil();
        setPrivateField(jwtUtil, "secret", "test-secret-key-for-unit-testing-minimum-32-chars");
        setPrivateField(jwtUtil, "expiration", 3600000L);
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("generatetoken - Backnon-emptystring")
    void generateToken_returnsNonNull() {
        String token = jwtUtil.generateToken(1L, "testuser");
        assertNotNull(token);
        assertTrue(token.length() > 0);
    }

    @Test
    @DisplayName("from tokeningetUsername")
    void getUsernameFromToken_returnsCorrectUsername() {
        String token = jwtUtil.generateToken(1L, "testuser");
        assertEquals("testuser", jwtUtil.getUsernameFromToken(token));
    }

    @Test
    @DisplayName("from tokeningetuserID")
    void getUserIdFromToken_returnsCorrectId() {
        String token = jwtUtil.generateToken(42L, "someuser");
        assertEquals(42L, jwtUtil.getUserIdFromToken(token));
    }

    @Test
    @DisplayName("verifyhas validtoken - Backtrue")
    void validateToken_validToken_returnsTrue() {
        String token = jwtUtil.generateToken(1L, "testuser");
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    @DisplayName("verifyNonevalidtoken - Backfalse")
    void validateToken_invalidToken_returnsFalse() {
        assertFalse(jwtUtil.validateToken("this.is.not.a.valid.token"));
    }

    @Test
    @DisplayName("verifyemptytoken - Backfalse")
    void validateToken_emptyToken_returnsFalse() {
        assertFalse(jwtUtil.validateToken(""));
        assertFalse(jwtUtil.validateToken(null));
    }

    @Test
    @DisplayName("sameoneusertwotimesgenerate token - Usernameonecause")
    void generateToken_sameUser_consistentUsername() {
        String token1 = jwtUtil.generateToken(5L, "userA");
        String token2 = jwtUtil.generateToken(5L, "userA");
        assertEquals(jwtUtil.getUsernameFromToken(token1), jwtUtil.getUsernameFromToken(token2));
        assertEquals(jwtUtil.getUserIdFromToken(token1), jwtUtil.getUserIdFromToken(token2));
    }

    @Test
    @DisplayName("not sameusergenerate token - Usernamenot same")
    void generateToken_differentUsers_differentUsername() {
        String token1 = jwtUtil.generateToken(1L, "userA");
        String token2 = jwtUtil.generateToken(2L, "userB");
        assertNotEquals(jwtUtil.getUsernameFromToken(token1), jwtUtil.getUsernameFromToken(token2));
        assertNotEquals(jwtUtil.getUserIdFromToken(token1), jwtUtil.getUserIdFromToken(token2));
    }
}
