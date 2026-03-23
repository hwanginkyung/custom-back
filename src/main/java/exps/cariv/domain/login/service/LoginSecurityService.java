package exps.cariv.domain.login.service;

import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginSecurityService {

    private static final String USER_FAIL_PREFIX = "auth:login:fail:user:";
    private static final String USER_LOCK_PREFIX = "auth:login:lock:user:";
    private static final String IP_LOGIN_FAIL_PREFIX = "auth:login:fail:ip-login:";
    private static final String REFRESH_ATTEMPT_PREFIX = "auth:refresh:attempt:ip:";
    private static final String SIGNUP_ATTEMPT_PREFIX = "auth:signup:attempt:ip:";

    private final StringRedisTemplate redis;

    @Value("${app.security.login.max-failures-per-user:5}")
    private int maxFailuresPerUser;

    @Value("${app.security.login.lock-minutes:15}")
    private int lockMinutes;

    @Value("${app.security.login.max-attempts-per-ip:100}")
    private int maxAttemptsPerIp;

    @Value("${app.security.login.ip-window-minutes:10}")
    private int ipWindowMinutes;

    @Value("${app.security.refresh.max-attempts-per-ip:120}")
    private int maxRefreshAttemptsPerIp;

    @Value("${app.security.refresh.window-minutes:10}")
    private int refreshWindowMinutes;

    @Value("${app.security.signup.max-attempts-per-ip:20}")
    private int maxSignupAttemptsPerIp;

    @Value("${app.security.signup.window-minutes:60}")
    private int signupWindowMinutes;

    private final FallbackCounterStore fallbackUserFailures = new FallbackCounterStore();
    private final FallbackCounterStore fallbackUserLocks = new FallbackCounterStore();
    private final FallbackCounterStore fallbackIpLoginFailures = new FallbackCounterStore();
    private final FallbackCounterStore fallbackIpActionAttempts = new FallbackCounterStore();

    public void assertAllowed(String loginId, String clientIp) {
        String normalizedLoginId = normalizeLoginId(loginId);
        String normalizedIp = normalizeIp(clientIp);
        String userFailKey = userFailKey(normalizedLoginId);
        String userLockKey = lockKey(normalizedLoginId);
        String ipLoginFailKey = ipLoginFailKey(normalizedIp, normalizedLoginId);

        try {
            Boolean locked = redis.hasKey(userLockKey);
            if (Boolean.TRUE.equals(locked)) {
                log.warn("[LoginSecurity] blocked by user lock loginId={}, ip={}", normalizedLoginId, normalizedIp);
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }

            Long ipAttempts = parseLong(redis.opsForValue().get(ipLoginFailKey));
            if (ipAttempts != null && ipAttempts >= maxAttemptsPerIp) {
                log.warn("[LoginSecurity] blocked by ip+login rate-limit loginId={}, ip={}, attempts={}, maxAttempts={}",
                        normalizedLoginId, normalizedIp, ipAttempts, maxAttemptsPerIp);
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            // Redis 장애 시 in-memory fallback으로 brute-force 방어 유지 (fail-closed)
            log.error("[LoginSecurity] Redis unavailable, using in-memory fallback loginId={}, ip={}", normalizedLoginId, normalizedIp, e);

            if (fallbackUserLocks.isActive(userLockKey)) {
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }

            Long ipAttempts = fallbackIpLoginFailures.get(ipLoginFailKey);
            if (ipAttempts != null && ipAttempts >= maxAttemptsPerIp) {
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }

            Long userFailures = fallbackUserFailures.get(userFailKey);
            if (userFailures != null && userFailures >= maxFailuresPerUser) {
                fallbackUserLocks.set(userLockKey, Duration.ofMinutes(lockMinutes));
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }
        }
    }

    public void onFailure(String loginId, String clientIp) {
        String normalizedLoginId = normalizeLoginId(loginId);
        String normalizedIp = normalizeIp(clientIp);
        String userFailKey = userFailKey(normalizedLoginId);
        String userLockKey = lockKey(normalizedLoginId);
        String ipLoginFailKey = ipLoginFailKey(normalizedIp, normalizedLoginId);

        try {
            long userFailures = incrementWithTtl(
                    userFailKey,
                    Duration.ofMinutes(ipWindowMinutes)
            );

            incrementWithTtl(
                    ipLoginFailKey,
                    Duration.ofMinutes(ipWindowMinutes)
            );

            if (userFailures >= maxFailuresPerUser) {
                redis.opsForValue().set(
                        userLockKey,
                        "1",
                        Duration.ofMinutes(lockMinutes)
                );
                redis.delete(userFailKey);
                log.warn("[LoginSecurity] user locked loginId={}, lockMinutes={}", normalizedLoginId, lockMinutes);
            }
        } catch (Exception e) {
            // Redis 장애 시 in-memory fallback으로 실패 횟수 기록 (fail-closed)
            log.error("[LoginSecurity] Redis unavailable for failure tracking, using in-memory fallback loginId={}, ip={}", normalizedLoginId, normalizedIp, e);
            long userFailures = fallbackUserFailures.increment(userFailKey, Duration.ofMinutes(ipWindowMinutes));
            fallbackIpLoginFailures.increment(ipLoginFailKey, Duration.ofMinutes(ipWindowMinutes));
            if (userFailures >= maxFailuresPerUser) {
                fallbackUserLocks.set(userLockKey, Duration.ofMinutes(lockMinutes));
                fallbackUserFailures.remove(userFailKey);
            }
        }
    }

    public void onSuccess(String loginId, String clientIp) {
        String normalizedLoginId = normalizeLoginId(loginId);
        String normalizedIp = normalizeIp(clientIp);
        String userFailKey = userFailKey(normalizedLoginId);
        String userLockKey = lockKey(normalizedLoginId);
        String ipLoginFailKey = ipLoginFailKey(normalizedIp, normalizedLoginId);
        try {
            redis.delete(userFailKey);
            redis.delete(userLockKey);
            redis.delete(ipLoginFailKey);
        } catch (Exception e) {
            log.error("[LoginSecurity] success cleanup failed, clearing in-memory fallback loginId={}, ip={}", normalizedLoginId, normalizedIp, e);
        } finally {
            fallbackUserFailures.remove(userFailKey);
            fallbackUserLocks.remove(userLockKey);
            fallbackIpLoginFailures.remove(ipLoginFailKey);
        }
    }

    /**
     * refresh 요청 단순 IP rate limit.
     */
    public void assertRefreshAllowed(String clientIp) {
        assertIpRateLimit(
                REFRESH_ATTEMPT_PREFIX,
                clientIp,
                maxRefreshAttemptsPerIp,
                refreshWindowMinutes,
                "refresh"
        );
    }

    /**
     * signup 요청 단순 IP rate limit.
     */
    public void assertSignupAllowed(String clientIp) {
        assertIpRateLimit(
                SIGNUP_ATTEMPT_PREFIX,
                clientIp,
                maxSignupAttemptsPerIp,
                signupWindowMinutes,
                "signup"
        );
    }

    private void assertIpRateLimit(String keyPrefix,
                                   String clientIp,
                                   int maxAttempts,
                                   int windowMinutes,
                                   String action) {
        String normalizedIp = normalizeIp(clientIp);
        String key = keyPrefix + normalizedIp;

        try {
            long attempts = incrementWithTtl(
                    key,
                    Duration.ofMinutes(Math.max(1, windowMinutes))
            );
            if (attempts > maxAttempts) {
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            // Redis 장애 시 in-memory fallback으로 rate-limit 유지 (fail-closed)
            log.error("[LoginSecurity] {} rate-limit check failed, using in-memory fallback ip={}", action, normalizedIp, e);
            long attempts = fallbackIpActionAttempts.increment(
                    key,
                    Duration.ofMinutes(Math.max(1, windowMinutes))
            );
            if (attempts > maxAttempts) {
                throw new CustomException(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."
                );
            }
        }
    }

    private long incrementWithTtl(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        long value = (count == null) ? 0L : count;
        if (value <= 1L) {
            redis.expire(key, ttl);
        }
        return value;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String userFailKey(String loginId) {
        return USER_FAIL_PREFIX + loginId;
    }

    private String lockKey(String loginId) {
        return USER_LOCK_PREFIX + loginId;
    }

    private String ipLoginFailKey(String ip, String loginId) {
        return IP_LOGIN_FAIL_PREFIX + ip + ":" + loginId;
    }

    private String normalizeLoginId(String loginId) {
        if (loginId == null) return "unknown";
        return loginId.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return "unknown";
        return clientIp.trim();
    }

    private static final class FallbackCounterStore {
        private record Entry(long count, long expiresAtMillis) {}

        private static final long MIN_TTL_MILLIS = 1_000L;
        private static final int CLEANUP_INTERVAL_OPS = 64;

        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
        private final AtomicInteger opCounter = new AtomicInteger(0);

        long increment(String key, Duration ttl) {
            long now = System.currentTimeMillis();
            long ttlMillis = Math.max(MIN_TTL_MILLIS, ttl.toMillis());
            long nextExpireAt = now + ttlMillis;
            Entry updated = map.compute(key, (k, old) -> {
                if (old == null || old.expiresAtMillis <= now) {
                    return new Entry(1L, nextExpireAt);
                }
                return new Entry(old.count + 1L, old.expiresAtMillis);
            });
            maybeCleanup(now);
            return updated.count;
        }

        Long get(String key) {
            long now = System.currentTimeMillis();
            Entry entry = map.get(key);
            if (entry == null) return null;
            if (entry.expiresAtMillis <= now) {
                map.remove(key, entry);
                return null;
            }
            return entry.count;
        }

        boolean isActive(String key) {
            return get(key) != null;
        }

        void set(String key, Duration ttl) {
            long now = System.currentTimeMillis();
            long ttlMillis = Math.max(MIN_TTL_MILLIS, ttl.toMillis());
            map.put(key, new Entry(1L, now + ttlMillis));
            maybeCleanup(now);
        }

        void remove(String key) {
            map.remove(key);
        }

        private void maybeCleanup(long now) {
            if (opCounter.incrementAndGet() % CLEANUP_INTERVAL_OPS != 0) return;
            map.entrySet().removeIf(e -> e.getValue().expiresAtMillis <= now);
        }
    }
}
