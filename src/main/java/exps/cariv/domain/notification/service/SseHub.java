package exps.cariv.domain.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class SseHub {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_EMITTERS_PER_USER = 3; // 유저당 최대 연결 수

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private String key(Long companyId, Long userId) {
        return companyId + ":" + userId;
    }

    public SseEmitter subscribe(Long companyId, Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        String k = key(companyId, userId);

        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(k, kk -> new CopyOnWriteArrayList<>());

        // 유저당 최대 연결 수 초과 시 가장 오래된 emitter 정리
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = list.isEmpty() ? null : list.remove(0);
            if (oldest != null) {
                try { oldest.complete(); } catch (Exception ignore) {}
            }
        }

        list.add(emitter);

        emitter.onCompletion(() -> remove(k, emitter));
        emitter.onTimeout(() -> remove(k, emitter));
        emitter.onError(e -> {
            remove(k, emitter);
            try { emitter.complete(); } catch (Exception ignore) { }
        });

        // keep-alive
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .reconnectTime(3_000)
                    .data("ok"));
        } catch (IOException ignore) {
            remove(k, emitter);
        }

        return emitter;
    }

    /**
     * 30초마다 heartbeat 전송.
     * 프록시/로드밸런서/브라우저가 유휴 연결을 끊지 않도록 유지.
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        if (emitters.isEmpty()) return;

        emitters.forEach((key, list) -> {
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (Exception e) {
                    dead.add(emitter);
                }
            }
            dead.forEach(em -> remove(key, em));
        });
    }

    /** 비동기 SSE push — 느린 클라이언트가 트랜잭션을 블로킹하지 않도록 */
    @Async("ssePushExecutor")
    public void push(Long companyId, Long userId, String event, Object data) {
        String k = key(companyId, userId);
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(k);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        dead.forEach(em -> remove(k, em));
    }

    private void remove(String key, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(key);
        if (list == null) return;
        list.remove(emitter);
        // 빈 리스트는 맵에서 제거 → 메모리 누수 방지
        if (list.isEmpty()) {
            emitters.remove(key, list);
        }
    }
}
