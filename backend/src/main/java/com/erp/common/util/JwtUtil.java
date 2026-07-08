package com.erp.common.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret:erp_jwt_secret_key_2024_change_in_production}") String secret,
                   @Value("${jwt.expiration:86400000}") long expiration) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    public String generateToken(String userId, String username, String displayName) {
        return generateToken(userId, username, displayName, null);
    }

    public String generateToken(String userId, String username, String displayName, String roleCode) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);
        JwtBuilder b = Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("displayName", displayName);
        if (roleCode != null && !roleCode.isBlank()) b.claim("roleCode", roleCode);
        return b.issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }
}
