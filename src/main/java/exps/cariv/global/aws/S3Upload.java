package exps.cariv.global.aws;

import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Upload {

    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "jpg", "jpeg", "png", "tiff", "tif", "bmp", "gif",
            "xlsx", "xls", "docx", "doc", "csv"
    );

    // 기존 메서드 유지
    public String upload(byte[] fileData, String fileName, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(fileName)
                .contentType(contentType)
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(fileData));
        return String.format("https://%s.s3.amazonaws.com/%s", bucket, fileName);
    }

    /** ✅ 새로 추가: 임시파일(Path) -> S3 업로드, "key" 반환 */
    public String uploadRawDocument(Path filePath, String originalFilename, Long companyId, Long documentId, String contentType) {
        String safeName = safe(originalFilename);
        String key = "raw-documents/"
                + companyId + "/"
                + documentId + "/"
                + UUID.randomUUID() + "-" + safeName;

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        s3Client.putObject(req, RequestBody.fromFile(filePath));
        return key; // ✅ Documents에는 보통 이 key를 저장
    }

    public String uploadCustomsPhoto(Path filePath,
                                     String originalFilename,
                                     Long companyId,
                                     Long requestId,
                                     String category,
                                     String contentType) {
        String safeName = safe(originalFilename);
        String key = "customs-requests/"
                + companyId + "/"
                + requestId + "/"
                + category + "/"
                + UUID.randomUUID() + "-" + safeName;

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        s3Client.putObject(req, RequestBody.fromFile(filePath));
        return key;
    }

    public String uploadBaseInfoDocument(byte[] fileData, String originalFilename, Long companyId, String documentType, String contentType) {
        String key = "base-info/"
                + companyId + "/"
                + documentType.toLowerCase()
                + "/latest";

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(fileData));
        return key;
    }

    /**
     * MultipartFile -> S3 업로드, UploadResult 반환.
     * RegistrationUploadService 등에서 사용.
     */
    public UploadResult uploadRawDocument(Long companyId, Long documentId, MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            validateFileExtension(originalFilename);
            String safeName = safe(originalFilename);
            String ct = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            String key = "raw-documents/"
                    + companyId + "/"
                    + documentId + "/"
                    + UUID.randomUUID() + "-" + safeName;

            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(ct)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return new UploadResult(key, originalFilename, ct, file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("S3 업로드 중 파일 읽기 실패", e);
        }
    }

    /** 단순 문서 업로드(OCR 없음) — 경락사실확인서, 매매계약서 등 */
    public UploadResult uploadVehicleDocument(Long companyId, String docCategory, MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            validateFileExtension(originalFilename);
            String safeName = safe(originalFilename);
            String ct = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            String key = "vehicle-documents/"
                    + companyId + "/"
                    + docCategory + "/"
                    + UUID.randomUUID() + "-" + safeName;

            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(ct)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return new UploadResult(key, originalFilename, ct, file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("S3 업로드 중 파일 읽기 실패", e);
        }
    }

    /**
     * customs request 전용 첨부파일 업로드.
     * path: customs-requests/{companyId}/{requestId}/{category}/{uuid}-{filename}
     */
    public UploadResult uploadCustomsPhoto(Long companyId, Long requestId, String category, MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            validateFileExtension(originalFilename);
            String safeName = safe(originalFilename);
            String ct = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            String key = "customs-requests/"
                    + companyId + "/"
                    + requestId + "/"
                    + category + "/"
                    + UUID.randomUUID() + "-" + safeName;

            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(ct)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return new UploadResult(key, originalFilename, ct, file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("S3 업로드 중 파일 읽기 실패", e);
        }
    }

    public record UploadResult(String s3Key, String originalFilename, String contentType, long sizeBytes) {}

    /**
     * S3 key 기준 파일 삭제.
     * 존재하지 않는 key 삭제는 S3 특성상 성공으로 처리된다(idempotent).
     */
    public void deleteByKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            DeleteObjectRequest req = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.deleteObject(req);
        } catch (Exception e) {
            log.warn("S3 삭제 실패 key={}", key, e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "첨부파일 삭제 중 오류가 발생했습니다.");
        }
    }

    /** 필요하면 URL 생성도 별도 제공 */
    public String toUrl(String key) {
        return String.format("https://%s.s3.amazonaws.com/%s", bucket, key);
    }

    private String safe(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replaceAll("[\\\\/<>:\"|?*]", "_");
    }

    private void validateFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return;
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx < 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "파일 확장자가 없습니다.");
        }
        String ext = filename.substring(dotIdx + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 파일 형식입니다: " + ext);
        }
    }
}
