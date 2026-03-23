package exps.cariv.global.config;


import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
@Slf4j
public class UpstageConfig {


    @Value("${upstage.api-key}")
    private String apiKey;

    @Value("${upstage.http.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${upstage.http.response-timeout-sec:60}")
    private int responseTimeoutSec;

    @Value("${upstage.http.max-connections:50}")
    private int maxConnections;

    @Value("${upstage.http.pending-acquire-timeout-ms:5000}")
    private int pendingAcquireTimeoutMs;

    @Value("${upstage.http.max-idle-sec:20}")
    private int maxIdleSec;

    @Value("${upstage.http.max-life-sec:300}")
    private int maxLifeSec;

    @Value("${upstage.http.evict-interval-sec:30}")
    private int evictIntervalSec;

    @Bean
    public WebClient upstageWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("upstage-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeoutMs))
                .maxIdleTime(Duration.ofSeconds(maxIdleSec))
                .maxLifeTime(Duration.ofSeconds(maxLifeSec))
                .evictInBackground(Duration.ofSeconds(evictIntervalSec))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSec));

        log.info("[UpstageConfig] pool initialized maxConnections={}, pendingAcquireTimeoutMs={}, maxIdleSec={}, maxLifeSec={}, evictIntervalSec={}, connectTimeoutMs={}, responseTimeoutSec={}",
                maxConnections, pendingAcquireTimeoutMs, maxIdleSec, maxLifeSec, evictIntervalSec,
                connectTimeoutMs, responseTimeoutSec);

        return WebClient.builder()
                .baseUrl("https://api.upstage.ai/v1")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(20 * 1024 * 1024) // 20MB
                )
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }
}
