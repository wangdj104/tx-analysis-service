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
    @Test void credentialStampIsOpaqueBoundToUserAndRequiresTheServerSecret() throws Exception {
        String bcrypt=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("SyntheticStrong!123");
        String token=jwtUtil.generateToken(7L,"synthetic",bcrypt);
        assertTrue(jwtUtil.matchesCredential(token,7L,bcrypt));assertFalse(jwtUtil.matchesCredential(token,8L,bcrypt));
        assertFalse(jwtUtil.matchesCredential(token,7L,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("SyntheticOther!123")));
        String payload=new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(payload.contains(bcrypt));assertFalse(payload.contains("SyntheticStrong!123"));
        assertFalse(jwtUtil.matchesCredential(jwtUtil.generateToken(7L,"synthetic"),7L,bcrypt));
        JwtUtil other=new JwtUtil();setPrivateField(other,"secret","another-synthetic-server-secret-1234567890123456789");setPrivateField(other,"expiration",3600000L);
        String otherToken=other.generateToken(7L,"synthetic",bcrypt);
        com.fasterxml.jackson.databind.JsonNode first=new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        com.fasterxml.jackson.databind.JsonNode second=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new String(java.util.Base64.getUrlDecoder().decode(otherToken.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(first.get("credentialStamp").asText(),second.get("credentialStamp").asText());
    }
}
