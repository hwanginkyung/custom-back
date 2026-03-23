package exps.cariv.domain.export.dto.response;

import exps.cariv.domain.export.dto.ExportSnapshot;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;

import java.time.Instant;

/**
 * 수출신고필증 문서 + OCR 결과 응답.
 */
public record ExportDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        ExportSnapshot snapshot,
        OcrFieldResult ocrResult
) {}
