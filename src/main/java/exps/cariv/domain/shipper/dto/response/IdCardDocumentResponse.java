package exps.cariv.domain.shipper.dto.response;

import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.shipper.dto.IdCardSnapshot;

import java.time.Instant;

/**
 * 신분증 문서 + OCR 결과 응답.
 */
public record IdCardDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        IdCardSnapshot snapshot,
        OcrFieldResult ocrResult
) {}
