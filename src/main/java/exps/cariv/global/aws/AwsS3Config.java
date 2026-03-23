package exps.cariv.global.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

@Configuration
public class AwsS3Config {

    @Value("${cloud.aws.credentials.access-key}")
    private String accessKey;

    @Value("${cloud.aws.credentials.secret-key}")
    private String secretKey;

    @Value("${cloud.aws.region.static}")
    private String region;

    @Value("${cloud.aws.s3.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${cloud.aws.s3.socket-timeout-ms:15000}")
    private int socketTimeoutMs;

    @Value("${cloud.aws.s3.api-call-timeout-ms:30000}")
    private int apiCallTimeoutMs;

    @Value("${cloud.aws.s3.api-call-attempt-timeout-ms:20000}")
    private int apiCallAttemptTimeoutMs;

    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .httpClientBuilder(
                        UrlConnectionHttpClient.builder()
                                .connectionTimeout(Duration.ofMillis(Math.max(1000, connectTimeoutMs)))
                                .socketTimeout(Duration.ofMillis(Math.max(1000, socketTimeoutMs)))
                )
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofMillis(Math.max(1000, apiCallTimeoutMs)))
                                .apiCallAttemptTimeout(Duration.ofMillis(Math.max(1000, apiCallAttemptTimeoutMs)))
                                .build()
                )
                .build();
    }
}
