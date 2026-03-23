package exps.cariv.domain.ocr.service;

import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis BRPOP 기반 OCR 워커.
 * - Poller: Redis에서 jobId를 읽어 회사별 대기열에 적재
 * - Dispatcher: 회사별 라운드로빈으로 공정하게 작업 할당
 * - Processor pool: bounded worker pool에서 실제 OCR 처리
 */
@Component
@Slf4j
public class OcrJobWorker {

    private final OcrParseJobRepository jobRepo;
    private final OcrJobProcessor jobProcessor;
    private final StringRedisTemplate redisTemplate;
    private final OcrQueueService ocrQueueService;

    @Value("${app.ocr.worker.max-concurrency:${upstage.guard.global-permits:1}}")
    private int maxConcurrency;

    @Value("${app.ocr.worker.max-inflight-per-company:1}")
    private int maxInflightPerCompany;

    @Value("${app.ocr.worker.dispatch-wait-ms:25}")
    private long dispatchWaitMs;

    @Value("${app.ocr.worker.stale-batch-size:100}")
    private int staleBatchSize;

    @Value("${app.ocr.worker.stale-backoff-base-seconds:30}")
    private long staleBackoffBaseSeconds;

    @Value("${app.ocr.worker.stale-backoff-max-seconds:1800}")
    private long staleBackoffMaxSeconds;

    private static final long BACKOFF_INITIAL_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 60_000;
    private static final int LOG_SUPPRESS_INTERVAL = 30;
    private static final int TRANSIENT_JOB_RETRY_MAX = 2;

    private static final String TRANSIENT_RETRY_KEY_PREFIX = "ocr:jobs:transient-retry:";
    private static final String STALE_RETRY_COUNT_KEY_PREFIX = "ocr:jobs:stale-retry-count:";
    private static final String STALE_RETRY_NEXT_KEY_PREFIX = "ocr:jobs:stale-retry-next:";

    private final ConcurrentHashMap<Long, Queue<Long>> pendingByCompany = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> roundRobinCompanies = new ConcurrentLinkedQueue<>();
    private final java.util.Set<Long> companyQueuedForRoundRobin = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, AtomicInteger> inflightByCompany = new ConcurrentHashMap<>();
    private final AtomicInteger inflightTotal = new AtomicInteger(0);
    private final AtomicInteger workerIdx = new AtomicInteger(0);

    private volatile boolean running = true;
    private volatile Thread pollerThread;
    private volatile Thread dispatcherThread;
    private volatile ExecutorService processingPool;

    public OcrJobWorker(OcrParseJobRepository jobRepo,
                        OcrJobProcessor jobProcessor,
                        StringRedisTemplate redisTemplate,
                        OcrQueueService ocrQueueService) {
        this.jobRepo = jobRepo;
        this.jobProcessor = jobProcessor;
        this.redisTemplate = redisTemplate;
        this.ocrQueueService = ocrQueueService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startWorker() {
        if (processingPool != null) {
            return;
        }
        running = true;

        int effectiveConcurrency = Math.max(1, maxConcurrency);
        processingPool = Executors.newFixedThreadPool(effectiveConcurrency, r -> {
            Thread t = new Thread(r, "ocr-worker-" + workerIdx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        pollerThread = new Thread(this::pollLoop, "ocr-queue-poller");
        pollerThread.setDaemon(true);
        pollerThread.start();

        dispatcherThread = new Thread(this::dispatchLoop, "ocr-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        log.info("[OcrJobWorker] started poller+dispatcher with maxConcurrency={} maxInflightPerCompany={}",
                effectiveConcurrency, Math.max(1, maxInflightPerCompany));
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        if (processingPool != null) {
            processingPool.shutdownNow();
            try {
                if (!processingPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("[OcrJobWorker] processing pool did not terminate cleanly within timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processingPool = null;
        }
        log.info("[OcrJobWorker] worker stopped");
    }

    private void pollLoop() {
        String queueKey = ocrQueueService.getQueueKey();
        long backoffMs = BACKOFF_INITIAL_MS;
        int consecutiveErrors = 0;

        while (running) {
            try {
                if (Thread.currentThread().isInterrupted()) break;

                String jobIdStr = redisTemplate.opsForList().rightPop(queueKey, Duration.ofSeconds(2));

                backoffMs = BACKOFF_INITIAL_MS;
                consecutiveErrors = 0;

                if (jobIdStr == null) {
                    continue;
                }

                Long jobId = parseJobId(jobIdStr);
                if (jobId == null) {
                    ocrQueueService.enqueueDlq(jobIdStr, "INVALID_JOB_ID");
                    continue;
                }
                ocrQueueService.markDequeued(jobId);

                Optional<OcrParseJob> jobOpt = jobRepo.findById(jobId);
                if (jobOpt.isEmpty()) {
                    ocrQueueService.enqueueDlq(jobIdStr, "JOB_NOT_FOUND");
                    log.warn("[OcrJobWorker] polled job not found id={} (skip)", jobId);
                    continue;
                }

                Long companyId = jobOpt.get().getCompanyId();
                enqueuePending(companyId, jobId);

            } catch (Exception e) {
                if (e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }

                consecutiveErrors++;
                if (consecutiveErrors == 1 || consecutiveErrors % LOG_SUPPRESS_INTERVAL == 0) {
                    log.error("[OcrJobWorker] poll loop error (consecutiveErrors={}), backoff={}ms",
                            consecutiveErrors, backoffMs, e);
                }

                sleepQuietly(backoffMs);
                backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
            }
        }
    }

    private void dispatchLoop() {
        while (running) {
            try {
                if (Thread.currentThread().isInterrupted()) break;

                if (inflightTotal.get() >= Math.max(1, maxConcurrency)) {
                    sleepQuietly(dispatchWaitMs);
                    continue;
                }

                Long companyId = roundRobinCompanies.poll();
                if (companyId == null) {
                    sleepQuietly(dispatchWaitMs);
                    continue;
                }

                companyQueuedForRoundRobin.remove(companyId);

                Queue<Long> companyQueue = pendingByCompany.get(companyId);
                if (companyQueue == null || companyQueue.isEmpty()) {
                    cleanupCompanyState(companyId, companyQueue);
                    continue;
                }

                AtomicInteger companyInflight = inflightByCompany.computeIfAbsent(companyId, id -> new AtomicInteger(0));
                if (companyInflight.get() >= Math.max(1, maxInflightPerCompany)) {
                    enqueueCompanyForDispatch(companyId);
                    sleepQuietly(dispatchWaitMs);
                    continue;
                }

                Long jobId = companyQueue.poll();
                if (jobId == null) {
                    cleanupCompanyState(companyId, companyQueue);
                    continue;
                }

                int total = inflightTotal.incrementAndGet();
                int perCompany = companyInflight.incrementAndGet();

                if (!companyQueue.isEmpty()) {
                    enqueueCompanyForDispatch(companyId);
                }

                try {
                    processingPool.execute(() -> {
                        try {
                            claimAndProcess(jobId);
                        } finally {
                            int leftTotal = inflightTotal.decrementAndGet();
                            int leftCompany = companyInflight.decrementAndGet();

                            Queue<Long> q = pendingByCompany.get(companyId);
                            if (q != null && !q.isEmpty()) {
                                enqueueCompanyForDispatch(companyId);
                            } else if (leftCompany <= 0) {
                                cleanupCompanyState(companyId, q);
                            }

                            log.debug("[OcrJobWorker] finished jobId={} companyId={} inflightTotal={} inflightCompany={}",
                                    jobId, companyId, leftTotal, Math.max(leftCompany, 0));
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    inflightTotal.decrementAndGet();
                    companyInflight.decrementAndGet();
                    enqueuePending(companyId, jobId);
                    sleepQuietly(dispatchWaitMs);
                    log.warn("[OcrJobWorker] processing pool rejected jobId={}, re-enqueued", jobId, ex);
                }

                log.debug("[OcrJobWorker] dispatched jobId={} companyId={} inflightTotal={} inflightCompany={}",
                        jobId, companyId, total, perCompany);

            } catch (Exception e) {
                log.error("[OcrJobWorker] dispatch loop error", e);
                sleepQuietly(dispatchWaitMs);
            }
        }
    }

    private void enqueuePending(Long companyId, Long jobId) {
        Queue<Long> queue = pendingByCompany.computeIfAbsent(companyId, id -> new ConcurrentLinkedQueue<>());
        queue.offer(jobId);
        enqueueCompanyForDispatch(companyId);
    }

    private void enqueueCompanyForDispatch(Long companyId) {
        if (companyQueuedForRoundRobin.add(companyId)) {
            roundRobinCompanies.offer(companyId);
        }
    }

    private void cleanupCompanyState(Long companyId, Queue<Long> queue) {
        if (queue != null && queue.isEmpty()) {
            // remove 직전에 enqueue가 들어와도 putIfAbsent로 복구할 수 있도록 queue 레퍼런스 유지
            boolean removed = pendingByCompany.remove(companyId, queue);
            if (removed && !queue.isEmpty()) {
                pendingByCompany.putIfAbsent(companyId, queue);
            }
        } else if (queue == null) {
            pendingByCompany.remove(companyId);
        }

        companyQueuedForRoundRobin.remove(companyId);
        Queue<Long> latest = pendingByCompany.get(companyId);
        if (latest != null && !latest.isEmpty()) {
            enqueueCompanyForDispatch(companyId);
            return;
        }

        AtomicInteger inflight = inflightByCompany.get(companyId);
        if (inflight != null && inflight.get() <= 0) {
            inflightByCompany.remove(companyId, inflight);
        }
    }

    private Long parseJobId(String jobIdStr) {
        try {
            return Long.parseLong(jobIdStr);
        } catch (NumberFormatException e) {
            log.warn("[OcrJobWorker] invalid queue payload='{}' (skip)", jobIdStr);
            return null;
        }
    }

    /**
     * Job을 원자적으로 claim 후 처리.
     * 이미 다른 워커가 claim했으면 스킵.
     */
    private void claimAndProcess(Long jobId) {
        Instant now = Instant.now();
        int claimed = jobRepo.claim(jobId, OcrJobStatus.QUEUED, OcrJobStatus.PROCESSING, now);
        if (claimed == 0) {
            log.debug("[OcrJobWorker] jobId={} already claimed, skip", jobId);
            return;
        }
        try {
            jobProcessor.process(jobId);
            clearTransientRetryCounter(jobId);
            clearStaleRecoveryState(jobId);
        } catch (Exception e) {
            try {
                if (isTransientNetworkError(e) && retryTransientJob(jobId, e)) {
                    return;
                }
            } catch (Exception retryEx) {
                log.error("[OcrJobWorker] transient retry failed (Redis?), falling through to handleFailure. jobId={}", jobId, retryEx);
            }
            if (isExpectedBusinessFailure(e)) {
                log.warn("[OcrJobWorker] job failed by business validation jobId={}, saving failure in separate tx: {}",
                        jobId, summarizeError(e));
            } else {
                log.error("[OcrJobWorker] job failed jobId={}, saving failure in separate tx", jobId, e);
            }
            try {
                jobProcessor.handleFailure(jobId, e);
            } catch (Exception failEx) {
                log.error("[OcrJobWorker] handleFailure itself failed jobId={}", jobId, failEx);
            }
        }
    }

    private boolean retryTransientJob(Long jobId, Exception error) {
        Long retries = incrementWithTtl(transientRetryKey(jobId), Duration.ofHours(6));
        if (retries == null) {
            retries = 1L;
        }

        if (retries > TRANSIENT_JOB_RETRY_MAX) {
            log.warn("[OcrJobWorker] transient retry exhausted jobId={} retries={}, will mark FAILED",
                    jobId, retries);
            return false;
        }

        int requeued = jobRepo.requeue(jobId, OcrJobStatus.PROCESSING, OcrJobStatus.QUEUED);
        if (requeued == 0) {
            log.warn("[OcrJobWorker] transient retry skip jobId={} (requeue claim miss)", jobId);
            return false;
        }

        ocrQueueService.enqueue(jobId, true);
        log.warn("[OcrJobWorker] transient network error, re-enqueued jobId={} retry={}/{} cause={}",
                jobId, retries, TRANSIENT_JOB_RETRY_MAX, rootCauseName(error));
        return true;
    }

    private boolean isTransientNetworkError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String name = cur.getClass().getSimpleName();
            String msg = cur.getMessage();

            if ("WebClientRequestException".equals(name)
                    || "PrematureCloseException".equals(name)
                    || "ReadTimeoutException".equals(name)
                    || "ConnectTimeoutException".equals(name)) {
                return true;
            }
            if (msg != null && msg.contains("Connection has been closed BEFORE response")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String rootCauseName(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName();
    }

    private boolean isExpectedBusinessFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof IllegalStateException && cur.getMessage() != null) {
                String msg = cur.getMessage();
                if (msg.contains("OCR 차량 매칭 실패")
                        || msg.contains("OCR 차량 매칭 충돌")
                        || msg.contains("말소증이 아닙니다")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String summarizeError(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return msg.replaceAll("\\s+", " ").trim();
    }

    private String transientRetryKey(Long jobId) {
        return TRANSIENT_RETRY_KEY_PREFIX + jobId;
    }

    private String staleRetryCountKey(Long jobId) {
        return STALE_RETRY_COUNT_KEY_PREFIX + jobId;
    }

    private String staleRetryNextKey(Long jobId) {
        return STALE_RETRY_NEXT_KEY_PREFIX + jobId;
    }

    private void clearTransientRetryCounter(Long jobId) {
        redisTemplate.delete(transientRetryKey(jobId));
    }

    private void clearStaleRecoveryState(Long jobId) {
        redisTemplate.delete(staleRetryCountKey(jobId));
        redisTemplate.delete(staleRetryNextKey(jobId));
    }

    /**
     * Fallback:
     * 1) QUEUED 잔여 건을 batch로 재큐잉 (지수 백오프 적용)
     * 2) PROCESSING 고착 건을 FAILED로 마킹
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void recoverStaleJobs() {
        // 1) QUEUED 잔여 건 batch 복구
        List<OcrParseJob> staleQueued = jobRepo.findAllSystemWideByStatus(
                OcrJobStatus.QUEUED,
                PageRequest.of(0, Math.max(1, staleBatchSize))
        );

        int enqueued = 0;
        int skippedByBackoff = 0;

        for (OcrParseJob job : staleQueued) {
            if (shouldRecoverNow(job.getId())) {
                ocrQueueService.enqueue(job.getId(), true);
                enqueued++;
            } else {
                skippedByBackoff++;
            }
        }

        if (!staleQueued.isEmpty()) {
            log.warn("[OcrJobWorker] fallback stale queued scan size={}, enqueued={}, skippedByBackoff={}",
                    staleQueued.size(), enqueued, skippedByBackoff);
        }

        // 2) PROCESSING 고착 복구: 10분 이상 PROCESSING 상태인 작업을 FAILED로 마킹
        Instant threshold = Instant.now().minus(Duration.ofMinutes(10));
        List<OcrParseJob> stuck = jobRepo.findAllSystemWideStuckJobs(OcrJobStatus.PROCESSING, threshold);
        for (OcrParseJob job : stuck) {
            log.error("[OcrJobWorker] stuck PROCESSING job detected id={}, marking FAILED", job.getId());
            job.markFailed("Stuck in PROCESSING for over 10 minutes (auto-recovered)");
            jobRepo.save(job);
        }
    }

    private boolean shouldRecoverNow(Long jobId) {
        try {
            String nextAllowedRaw = redisTemplate.opsForValue().get(staleRetryNextKey(jobId));
            long now = Instant.now().getEpochSecond();
            if (nextAllowedRaw != null) {
                long nextAllowed = Long.parseLong(nextAllowedRaw);
                if (nextAllowed > now) {
                    return false;
                }
            }

            Long attempt = incrementWithTtl(staleRetryCountKey(jobId), Duration.ofHours(12));
            if (attempt == null) {
                attempt = 1L;
            }

            long delaySeconds = computeBackoffSeconds(attempt);
            long nextAllowed = now + delaySeconds;
            redisTemplate.opsForValue().set(
                    staleRetryNextKey(jobId),
                    String.valueOf(nextAllowed),
                    Duration.ofSeconds(delaySeconds + 600)
            );
            return true;

        } catch (Exception e) {
            // Redis 장애 시에는 복구를 막지 않는다.
            log.warn("[OcrJobWorker] stale backoff state unavailable, recovering immediately jobId={}", jobId, e);
            return true;
        }
    }

    private Long incrementWithTtl(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return value;
    }

    private long computeBackoffSeconds(Long attempt) {
        long exponent = Math.max(0L, attempt - 1L);
        long seconds = staleBackoffBaseSeconds;

        // overflow 방지 + 상한 적용
        for (long i = 0; i < exponent; i++) {
            if (seconds >= staleBackoffMaxSeconds) {
                return staleBackoffMaxSeconds;
            }
            long next = seconds * 2;
            if (next < 0 || next > staleBackoffMaxSeconds) {
                return staleBackoffMaxSeconds;
            }
            seconds = next;
        }

        return Math.min(seconds, staleBackoffMaxSeconds);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
