package exps.customs.global.jwt;

import exps.customs.domain.login.entity.User;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String CLAIM_TYPE = "type";
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_COMPANY_ID = "companyId";
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";

    @Value("${jwt.secret}")
    private String secretKey;
    @Value("${jwt.access-token-expire-ms:1800000}")
    private long accessTokenExpireMs;
    @Value("${jwt.refresh-token-expire-ms:1209600000}")
    private long refreshTokenExpireMs;
    private final UserDetailsService userDetailsService;

    private Key cachedSigningKey;
    private static final int MIN_SECRET_BYTES = 32;

    @PostConstruct
    private void initSigningKey() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 길이가 부족합니다. 최소 " + MIN_SECRET_BYTES + "바이트 필요, 현재: " + keyBytes.length + "바이트"
            );
        }
        this.cachedSigningKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private Key getSigningKey() { return cachedSigningKey; }

    public String createAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpireMs);
        return Jwts.builder()
                .setSubject(user.getLoginId())
                .claim(CLAIM_TYPE, "access")
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_COMPANY_ID, user.getCompanyId())
                .claim(CLAIM_TENANT_ID, user.getCompanyId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String createRefreshToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpireMs);
        return Jwts.builder()
                .setSubject(user.getLoginId())
                .claim(CLAIM_TYPE, "refresh")
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_COMPANY_ID, user.getCompanyId())
                .claim(CLAIM_TENANT_ID, user.getCompanyId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims validateAndGetClaims(String token) {
        try {
            return getClaims(token);
        } catch (ExpiredJwtException e) {
            throw new CustomException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
    }

    public Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Authentication getAuthentication(Claims claims) {
        String loginId = claims.getSubject();
        UserDetails userDetails = userDetailsService.loadUserByUsername(loginId);
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    public boolean isTokenType(Claims claims, String expectedType) {
        if (claims == null || expectedType == null) return false;
        Object type = claims.get(CLAIM_TYPE);
        return expectedType.equals(type);
    }

    public Long extractTenantId(Claims claims) {
        if (claims == null) return null;
        Long tenantId = extractLongClaim(claims, CLAIM_TENANT_ID);
        if (tenantId != null && tenantId > 0L) return tenantId;
        Long companyId = extractLongClaim(claims, CLAIM_COMPANY_ID);
        if (companyId != null && companyId > 0L) return companyId;
        return null;
    }

    public Long extractUserId(Claims claims) {
        return extractLongClaim(claims, CLAIM_USER_ID);
    }

    private Long extractLongClaim(Claims claims, String key) {
        Object value = claims.get(key);
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
