package org.familyhealthcare.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWTutility class
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private Long expiration;

    /**
     * generateJWT Token
     *
     * @param userId   userID
     * @param username Username
     * @return JWT Token
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        return createToken(claims, username);
    }

    /** The opaque server-keyed stamp revokes prior sessions when the stored credential changes. */
    public String generateToken(Long userId, String username, String bcryptPassword) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("credentialStamp", credentialStamp(userId, bcryptPassword));
        return createToken(claims, username);
    }

    public boolean matchesCredential(String token, Long userId, String bcryptPassword) {
        try {
            Claims claims = getClaimsFromToken(token);
            if (!userId.equals(Long.valueOf(claims.get("userId").toString()))) return false;
            Object stamp = claims.get("credentialStamp");
            if (!(stamp instanceof String)) return false;
            return java.security.MessageDigest.isEqual(((String) stamp).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    credentialStamp(userId, bcryptPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) { return false; }
    }

    private String credentialStamp(Long userId, String bcryptPassword) {
        if (userId == null || bcryptPassword == null || !bcryptPassword.matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}"))
            throw new IllegalArgumentException("A current account credential is required.");
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String input = "family-health-credential-v1\n" + userId + "\n" + bcryptPassword;
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Credential validation is unavailable."); }
    }

    /**
     * CreateToken
     */
    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expirationDate)
                .signWith(SignatureAlgorithm.HS512, secret)
                .compact();
    }

    /**
     * from TokeningetUsername
     */
    public String getUsernameFromToken(String token) {
        return getClaimsFromToken(token).getSubject();
    }

    /**
     * from TokeningetuserID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return Long.valueOf(claims.get("userId").toString());
    }

    /**
     * from TokeningetpastperiodTime
     */
    public Date getExpirationDateFromToken(String token) {
        return getClaimsFromToken(token).getExpiration();
    }

    /**
     * from TokeningetClaims
     */
    private Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .setSigningKey(secret)
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * verifyTokenYesNohas valid
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * determineTokenYesNopastperiod
     */
    private boolean isTokenExpired(String token) {
        Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }
}
