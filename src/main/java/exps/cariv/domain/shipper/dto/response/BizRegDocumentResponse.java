package exps.cariv.domain.shipper.dto.response;

import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.shipper.dto.BizRegSnapshot;

import java.time.Instant;

/**
 * 사업자등록증 문서 + OCR 결과 응답.
 */
public record BizRegDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        BizRegSnapshot snapshot,
        OcrFieldResult ocrResult
) {}
