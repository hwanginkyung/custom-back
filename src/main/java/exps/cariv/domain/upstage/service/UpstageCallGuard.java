package exps.cariv.domain.upstage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Slf4j
@Component
public class UpstageCallGuard {

    /** 전역 동시 호출 수 — 현재 RPS=1이므로 1, 나중에 RPS 올리면 설정만 변경 */
    @Value("${upstage.guard.global-permits:1}")
    private int globalPermits;

    /** 회사별 동시 호출 수 */
    @Value("${upstage.guard.per-company-permits:1}")
    private int perCompanyPermits;

    /** permit 대기 타임아웃(초) */
    @Value("${upstage.guard.timeout-seconds:60}")
    private int timeoutSeconds;

    /** 회사별 Semaphore 맵 최대 크기 */
    private static final int MAX_COMPANY_SEMAPHORES = 500;

    private Semaphore global;
    private final ConcurrentHashMap<Long, Semaphore> perCompany = new ConcurrentHashMap<>();

    @PostConstruct
    private void init() {
        this.global = new Semaphore(globalPermits, true);
        log.info("[UpstageCallGuard] global={}, perCompany={}, timeout={}s",
                globalPermits, perCompanyPermits, timeoutSeconds);
    }

    private Semaphore companySem(Long companyId) {
        return perCompany.computeIfAbsent(companyId, id -> new Semaphore(perCompanyPermits, true));
    }

    /**
     * 30분마다 유휴 Semaphore 정리.
     * permit이 모두 반납된(=사용 중이 아닌) 엔트리를 제거하여 메모리 누수 방지.
     */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 1_800_000)
    public void cleanupIdleSemaphores() {
        int before = perCompany.size();
        if (before == 0) return;

        perCompany.entrySet().removeIf(entry -> {
            Semaphore sem = entry.getValue();
            // permit이 전부 반납된 상태 = 현재 사용 중이 아님
            return sem.availablePermits() == perCompanyPermits;
        });

        int removed = before - perCompany.size();
        if (removed > 0) {
            log.info("[UpstageCallGuard] cleaned up {} idle company semaphores (remaining={})",
                    removed, perCompany.size());
        } else if (before > MAX_COMPANY_SEMAPHORES) {
            log.warn("[UpstageCallGuard] company semaphore map remains large (size={})", before);
        }
    }

    public <T> T run(Long companyId, Supplier<T> action) {
        Semaphore company = companySem(companyId);

        boolean g = false;
        boolean c = false;

        try {
            g = global.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            if (!g) throw new IllegalStateException("Upstage global permit timeout");

            c = company.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            if (!c) throw new IllegalStateException("Upstage company permit timeout companyId=" + companyId);

            return action.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting Upstage permit", ie);
        } finally {
            if (c) company.release();
            if (g) global.release();
        }
    }
}
