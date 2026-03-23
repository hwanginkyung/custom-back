package exps.cariv.domain.contract.dto.response;

import exps.cariv.domain.contract.dto.ContractSnapshot;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;

import java.time.Instant;

public record ContractDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        ContractSnapshot snapshot,
        OcrFieldResult ocrResult
) {}
