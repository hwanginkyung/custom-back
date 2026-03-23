package exps.cariv.domain.upstage.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstageService {

    private final WebClient upstageWebClient;
    private final UpstageCallGuard callGuard;

    @Value("${upstage.retry.max-attempts:5}")
    private int maxAttempts;

    @Value("${upstage.retry.base-backoff-ms:400}")
    private long baseBackoffMs;

    @Value("${upstage.retry.max-backoff-ms:8000}")
    private long maxBackoffMs;

    @Value("${upstage.retry.jitter-ratio:0.2}")
    private double jitterRatio;

    @Value("${upstage.call-timeout-seconds:70}")
    private long callTimeoutSeconds;

    /**
     * Upstage Document Parse API 호출.
     * UpstageCallGuard를 통해 전역/회사별 동시성을 제어합니다.
     */
    public String parseDocuments(Long companyId, Resource resource, String filename) {
        return callGuard.run(companyId, () -> doParseDocuments(resource, filename));
    }

    private String doParseDocuments(Resource resource, String filename) {
        long start = System.nanoTime();

        String result = createRequest(resource, filename)
                .timeout(Duration.ofSeconds(Math.max(1, callTimeoutSeconds)))
                .retryWhen(retrySpec())
                .block();

        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Empty response from Upstage API");
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[Upstage API] document-digitization processed in {} ms", elapsedMs);
        return result;
    }

    private Mono<String> createRequest(Resource resource, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", "document-parse");
        builder.part("document", resource)
                .filename(filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);

        builder.part("chart_recognition", "true");
        builder.part("merge_multipage_tables", "true");
        builder.part("ocr", "auto");
        builder.part("output_formats", "[\"html\", \"markdown\"]");
        builder.part("coordinates", "true");
        builder.part("base64_encoding", "[\"table\"]");

        return upstageWebClient.post()
                .uri("/document-digitization")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchangeToMono(resp -> {
                    HttpStatusCode sc = resp.statusCode();
                    if (sc.is2xxSuccessful()) {
                        return resp.bodyToMono(String.class);
                    }
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(
                                    WebClientResponseException.create(
                                            sc.value(),
                                            sc.toString(),
                                            resp.headers().asHttpHeaders(),
                                            body.getBytes(),
                                            null
                                    )));
                });
    }

    private Retry retrySpec() {
        int retries = Math.max(0, maxAttempts - 1);
        Duration firstBackoff = Duration.ofMillis(Math.max(50, baseBackoffMs));
        Duration maxBackoff = Duration.ofMillis(Math.max(200, maxBackoffMs));
        double jitter = Math.min(0.9, Math.max(0.0, jitterRatio));

        return Retry.backoff(retries, firstBackoff)
                .maxBackoff(maxBackoff)
                .jitter(jitter)
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> log.warn(
                        "[Upstage API] retry {}/{} cause={}",
                        signal.totalRetries() + 1,
                        Math.max(1, maxAttempts),
                        rootCauseName(signal.failure())
                ));
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            int status = e.getStatusCode().value();
            return status == 429 || (status >= 500 && status <= 599);
        }
        if (t instanceof WebClientRequestException e) {
            return isRetryableRequestException(e);
        }
        if (t instanceof TimeoutException) {
            return true;
        }

        Throwable cur = t.getCause();
        while (cur != null) {
            if (cur instanceof TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean isRetryableRequestException(WebClientRequestException e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof IOException || cur instanceof TimeoutException) {
                return true;
            }
            String simpleName = cur.getClass().getSimpleName();
            if ("PrematureCloseException".equals(simpleName)
                    || "ReadTimeoutException".equals(simpleName)
                    || "ConnectTimeoutException".equals(simpleName)) {
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
}
