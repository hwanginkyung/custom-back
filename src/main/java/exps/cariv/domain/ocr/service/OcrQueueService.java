package exps.cariv.domain.ocr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis List 기반 OCR 작업 큐.
 * - enqueue: LPUSH (producer)
 * - dequeue: BRPOP (consumer, OcrJobWorker에서 호출)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrQueueService {

    private static final String QUEUE_KEY = "ocr:jobs:queue";
    private static final String DLQ_KEY = "ocr:jobs:dlq";
    private static final String QUEUED_MARKER_PREFIX = "ocr:jobs:queued:";
    private static final Duration QUEUED_MARKER_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;

    @Value("${app.ocr.queue.warn-size:1000}")
    private long queueWarnSize;

    /** Job ID를 큐에 추가 (기본: 중복 enqueue 방지) */
    public void enqueue(Long jobId) {
        enqueue(jobId, false);
    }

    /**
     * Job ID를 큐에 추가.
     * force=true인 경우 중복 여부와 관계없이 큐에 넣습니다(복구/재시도 경로).
     */
    public void enqueue(Long jobId, boolean force) {
        if (jobId == null || jobId <= 0) {
            log.warn("[OcrQueue] skip enqueue invalid jobId={}", jobId);
            return;
        }

        String payload = String.valueOf(jobId);
        String markerKey = markerKey(jobId);

        try {
            if (!force) {
                Boolean reserved = redisTemplate.opsForValue()
                        .setIfAbsent(markerKey, "1", QUEUED_MARKER_TTL);
                if (!Boolean.TRUE.equals(reserved)) {
                    log.debug("[OcrQueue] duplicate enqueue skipped jobId={}", jobId);
                    return;
                }
            } else {
                redisTemplate.opsForValue().set(markerKey, "1", QUEUED_MARKER_TTL);
            }

            redisTemplate.opsForList().leftPush(QUEUE_KEY, payload);
            log.debug("[OcrQueue] enqueued jobId={} force={}", jobId, force);
        } catch (Exception e) {
            // marker만 남아 enqueue가 막히는 상황 방지
            try {
                redisTemplate.delete(markerKey);
            } catch (Exception ignore) {
            }
            throw e;
        }
    }

    /** 큐 키 반환 (Worker에서 BRPOP 용) */
    public String getQueueKey() {
        return QUEUE_KEY;
    }

    /**
     * BRPOP 후 큐에서 빠진 job의 중복 방지 마커를 제거합니다.
     */
    public void markDequeued(Long jobId) {
        if (jobId == null || jobId <= 0) return;
        try {
            redisTemplate.delete(markerKey(jobId));
        } catch (Exception e) {
            log.warn("[OcrQueue] failed to clear queued marker jobId={}", jobId, e);
        }
    }

    /**
     * 잘못된 큐 payload를 DLQ에 저장합니다.
     */
    public void enqueueDlq(String payload, String reason) {
        String safePayload = payload == null ? "null" : payload;
        String safeReason = reason == null ? "UNKNOWN" : reason;
        String item = safeReason + "|" + safePayload;
        try {
            redisTemplate.opsForList().leftPush(DLQ_KEY, item);
            redisTemplate.opsForList().trim(DLQ_KEY, 0, 999);
        } catch (Exception e) {
            log.warn("[OcrQueue] failed to push DLQ item reason={} payload={}", safeReason, safePayload, e);
        }
    }

    @Scheduled(fixedDelayString = "${app.ocr.queue.monitor-interval-ms:60000}",
            initialDelayString = "${app.ocr.queue.monitor-initial-delay-ms:60000}")
    public void monitorQueueDepth() {
        try {
            Long size = redisTemplate.opsForList().size(QUEUE_KEY);
            if (size == null) return;
            if (size >= queueWarnSize) {
                log.warn("[OcrQueue] queue depth warning size={} threshold={}", size, queueWarnSize);
            } else {
                log.debug("[OcrQueue] queue depth size={}", size);
            }
        } catch (Exception e) {
            log.warn("[OcrQueue] queue depth monitor failed", e);
        }
    }

    private String markerKey(Long jobId) {
        return QUEUED_MARKER_PREFIX + jobId;
    }
}
