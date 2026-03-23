package exps.cariv.domain.vehicle.dto.response;

import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.vehicle.dto.OwnerIdCardSnapshot;

import java.time.Instant;

/**
 * 차량 소유자 신분증 문서 + OCR 결과 응답.
 */
public record OwnerIdCardDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        OwnerIdCardSnapshot snapshot,
        OcrFieldResult ocrResult
) {
}
