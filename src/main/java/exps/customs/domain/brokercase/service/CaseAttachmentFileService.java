package exps.customs.domain.brokercase.service;

import exps.customs.domain.brokercase.dto.CaseAttachmentFileResponse;
import exps.customs.domain.brokercase.entity.CaseAttachment;
import exps.customs.domain.brokercase.repository.BrokerCaseRepository;
import exps.customs.domain.brokercase.repository.CaseAttachmentRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import exps.customs.global.tenant.aspect.TenantFiltered;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaseAttachmentFileService {

    private final BrokerCaseRepository caseRepository;
    private final CaseAttachmentRepository attachmentRepository;
    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket:}")
    private String defaultBucket;

    @Value("${cloud.aws.s3.download-timeout-ms:10000}")
    private long downloadTimeoutMs;

    @TenantFiltered
    public CaseAttachmentFileResponse loadAttachment(Long caseId, Long attachmentId) {
        caseRepository.findById(caseId)
                .orElseThrow(() -> new CustomException(ErrorCode.CASE_NOT_FOUND));

        CaseAttachment attachment = attachmentRepository.findByIdAndBrokerCaseId(attachmentId, caseId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "첨부파일을 찾을 수 없습니다."));

        String filePath = trimToNull(attachment.getFilePath());
        if (filePath == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "첨부파일 경로가 비어 있습니다.");
        }

        byte[] bytes = loadBytes(filePath);
        if (bytes == null || bytes.length == 0) {
            throw new CustomException(ErrorCode.NOT_FOUND, "첨부파일 내용을 찾을 수 없습니다.");
        }

        String fileName = firstNonBlank(
                trimToNull(attachment.getFileName()),
                extractFileName(filePath),
                "attachment-" + attachmentId
        );
        String contentType = firstNonBlank(
                trimToNull(attachment.getContentType()),
                inferContentType(fileName),
                "application/octet-stream"
        );

        return CaseAttachmentFileResponse.builder()
                .bytes(bytes)
                .fileName(fileName)
                .contentType(contentType)
                .build();
    }

    private byte[] loadBytes(String filePath) {
        String normalized = trimToNull(filePath);
        if (normalized == null) {
            return null;
        }

        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            if (isPresignedS3Url(normalized)) {
                return downloadFromUrl(normalized);
            }
            S3Location locationFromUrl = resolveS3LocationFromHttpUrl(normalized);
            if (locationFromUrl != null) {
                byte[] fromS3 = downloadFromS3(locationFromUrl);
                if (fromS3 != null && fromS3.length > 0) {
                    return fromS3;
                }
            }
            return downloadFromUrl(normalized);
        }

        S3Location location = resolveS3Location(normalized);
        return downloadFromS3(location);
    }

    private byte[] downloadFromS3(S3Location location) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(location.bucket())
                    .key(location.key())
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (Exception e) {
            log.warn("[Attachment] S3 download failed bucket={}, key={}, cause={}",
                    location.bucket(), location.key(), e.getMessage());
            return null;
        }
    }

    private byte[] downloadFromUrl(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1000L, downloadTimeoutMs)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000L, downloadTimeoutMs)))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[Attachment] URL download failed status={}, url={}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("[Attachment] URL download error url={}, cause={}", url, e.getMessage());
            return null;
        }
    }

    private S3Location resolveS3Location(String filePath) {
        if (filePath.startsWith("s3://")) {
            String raw = filePath.substring("s3://".length());
            int slash = raw.indexOf('/');
            if (slash <= 0 || slash == raw.length() - 1) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 s3 경로입니다.");
            }
            String bucket = raw.substring(0, slash).trim();
            String key = raw.substring(slash + 1).trim();
            return new S3Location(bucket, key);
        }

        String bucket = trimToNull(defaultBucket);
        if (bucket == null) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "S3 버킷 설정이 없습니다.");
        }
        String key = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        return new S3Location(bucket, key);
    }

    private S3Location resolveS3LocationFromHttpUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = trimToNull(uri.getHost());
            String path = trimToNull(uri.getPath());
            if (host == null || path == null) {
                return null;
            }

            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            if (normalizedPath.isBlank()) {
                return null;
            }

            if (host.contains(".s3.") && host.endsWith(".amazonaws.com")) {
                int idx = host.indexOf(".s3.");
                if (idx > 0) {
                    String bucket = host.substring(0, idx);
                    return new S3Location(bucket, normalizedPath);
                }
            }

            if (host.startsWith("s3.") && host.endsWith(".amazonaws.com")) {
                int slash = normalizedPath.indexOf('/');
                if (slash <= 0 || slash == normalizedPath.length() - 1) {
                    return null;
                }
                String bucket = normalizedPath.substring(0, slash);
                String key = normalizedPath.substring(slash + 1);
                return new S3Location(bucket, key);
            }
            return null;
        } catch (Exception e) {
            log.debug("[Attachment] failed to parse S3 URL: {}", rawUrl);
            return null;
        }
    }

    private boolean isPresignedS3Url(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("x-amz-signature=") || lower.contains("x-amz-credential=");
    }

    private String extractFileName(String filePath) {
        String normalized = trimToNull(filePath);
        if (normalized == null) {
            return null;
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return normalized;
    }

    private String inferContentType(String fileName) {
        String value = trimToNull(fileName);
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record S3Location(String bucket, String key) {
    }
}
