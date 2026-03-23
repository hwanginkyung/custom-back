package exps.cariv.domain.customs.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 관세사 전송 결과 응답.
 * <p>전송 시 생성된 PDF 문서 목록을 반환한다.</p>
 */
public record CustomsSendResponse(
        Long customsRequestId,
        String status,
        List<GeneratedDoc> generatedDocuments
) {

    public record GeneratedDoc(
            String name,             // invoice.pdf, id_card.pdf(호환 키), deregistration.pdf
            String previewUrl,       // /api/customs/{requestId}/docs/{name}/preview
            String downloadUrl,      // /api/customs/{requestId}/docs/{name}?download=true
            String s3Key,            // generated PDF S3 key
            String s3Url,            // generated PDF public S3 url
            String sourceS3Key,      // generated 문서의 원본이 S3 문서일 때만 채움 (invoice는 null)
            long sizeBytes,          // generated PDF size
            Instant generatedAt      // generated timestamp
    ) {}
}
