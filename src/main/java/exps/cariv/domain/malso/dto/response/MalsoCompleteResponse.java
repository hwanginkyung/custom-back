package exps.cariv.domain.malso.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 말소 완료(파싱 완료 알림 진입) 화면 응답 DTO.
 */
public record MalsoCompleteResponse(
        Long vehicleId,
        String vehicleTitle,
        UploadedDocument uploadedDocument,
        List<FieldResult> fields,
        Summary summary
) {
    public record UploadedDocument(
            Long documentId,
            String s3Key,
            String documentName,
            String documentType,
            Long sizeBytes,
            Instant uploadedAt,
            Instant parsedAt
    ) {}

    public record FieldResult(
            String key,
            String label,
            String value,
            boolean success,
            String errorMessage
    ) {}

    public record Summary(
            int successCount,
            int errorCount
    ) {}
}
