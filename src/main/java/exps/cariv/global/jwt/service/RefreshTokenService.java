package exps.cariv.global.jwt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 기반 Refresh Token 관리.
 *
 * 저장 구조:  Hash  rt:{tokenHash} → { userId, loginId, expiresAt }
 * TTL:        토큰 만료 시각에 맞춰 자동 삭제 → revoke 관리/cleanup 불필요
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final String KEY_PREFIX = "rt:";
    private static final String USER_INDEX_PREFIX = "rtu:";
    private static final Duration USER_INDEX_TTL = Duration.ofDays(30);
    private static final HexFormat HEX = HexFormat.of();

    private final StringRedisTemplate redis;

    // ─── 저장 ────────────────────────────────────────

    public void saveNewToken(String refreshToken, Long userId, String loginId, Instant expiresAt) {
        String key = toKey(refreshToken);
        String userIndexKey = userIndexKey(userId);
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) return;

        redis.opsForHash().putAll(key, Map.of(
                "userId",    String.valueOf(userId),
                "loginId",   loginId,
                "expiresAt", String.valueOf(expiresAt.toEpochMilli())
        ));
        redis.expire(key, ttl);
        redis.opsForSet().add(userIndexKey, key);
        redis.expire(userIndexKey, USER_INDEX_TTL);

        log.debug("[RefreshToken] saved  key={}, userId={}, ttl={}s", key, userId, ttl.getSeconds());
    }

    // ─── 검증 ────────────────────────────────────────

    /**
     * 토큰이 Redis에 존재하면 유효. TTL로 만료 관리되므로 시간 비교 불필요.
     * @return userId (유효), empty (만료/revoked)
     */
    public Optional<RefreshTokenInfo> validate(String refreshToken) {
        String key = toKey(refreshToken);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) return Optional.empty();

        Long userId  = Long.parseLong((String) entries.get("userId"));
        String loginId = (String) entries.get("loginId");
        return Optional.of(new RefreshTokenInfo(userId, loginId));
    }

    // ─── 폐기 ────────────────────────────────────────

    /** 로그아웃 · 리프레시 시 기존 토큰 즉시 삭제 */
    public void revoke(String refreshToken) {
        String key = toKey(refreshToken);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        redis.delete(key);
        Object userIdObj = entries.get("userId");
        if (userIdObj instanceof String userIdStr) {
            String userIndexKey = USER_INDEX_PREFIX + userIdStr;
            redis.opsForSet().remove(userIndexKey, key);
        }
        log.debug("[RefreshToken] revoked key={}", key);
    }

    /** 비밀번호 변경/계정 비활성화 시 사용자 전체 refresh token 강제 폐기 */
    public void revokeAllByUserId(Long userId) {
        String userIndexKey = userIndexKey(userId);
        var members = redis.opsForSet().members(userIndexKey);
        if (members != null && !members.isEmpty()) {
            redis.delete(members);
        }
        redis.delete(userIndexKey);
        log.info("[RefreshToken] revoked all tokens userId={}, tokenCount={}", userId,
                members == null ? 0 : members.size());
    }

    // ─── 내부 ────────────────────────────────────────

    private String toKey(String rawToken) {
        return KEY_PREFIX + hashToken(rawToken);
    }

    private String userIndexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }

    private String hashToken(String token) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Redis에서 꺼낸 Refresh Token 정보 */
    public record RefreshTokenInfo(Long userId, String loginId) {}
}
