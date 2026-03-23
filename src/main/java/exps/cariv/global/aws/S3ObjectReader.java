package exps.cariv.global.aws;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class S3ObjectReader {

    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    /**
     * S3 key로 객체 바이트를 읽어옵니다. (없으면 null)
     */
    public byte[] readBytes(String key) {
        if (key == null || key.isBlank()) return null;

        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> is = s3Client.getObject(req)) {
            return is.readAllBytes();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read s3 object: " + key, e);
        }
    }

    /**
     * S3 key로 객체 바이트와 Content-Type을 읽어옵니다. (없으면 null)
     */
    public S3ObjectData readObject(String key) {
        if (key == null || key.isBlank()) return null;

        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> is = s3Client.getObject(req)) {
            GetObjectResponse response = is.response();
            return new S3ObjectData(is.readAllBytes(), response.contentType());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read s3 object: " + key, e);
        }
    }

    /**
     * S3 key로 InputStream을 반환합니다 (대용량 파일 스트리밍용).
     * 호출자가 반드시 close() 해야 합니다.
     * 없으면 null.
     */
    public ResponseInputStream<GetObjectResponse> readStream(String key) {
        if (key == null || key.isBlank()) return null;

        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try {
            return s3Client.getObject(req);
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * S3 key 메타데이터(크기/수정시각/Content-Type)를 조회합니다. (없으면 null)
     */
    public S3ObjectMeta readMeta(String key) {
        if (key == null || key.isBlank()) return null;

        HeadObjectRequest req = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try {
            HeadObjectResponse res = s3Client.headObject(req);
            return new S3ObjectMeta(
                    res.contentLength(),
                    res.lastModified(),
                    res.contentType()
            );
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    public record S3ObjectData(byte[] bytes, String contentType) {
    }

    public record S3ObjectMeta(Long sizeBytes, Instant lastModified, String contentType) {
    }
}
