package exps.cariv.domain.ocr.dto.response;

import exps.cariv.domain.document.entity.DocumentType;

public record OcrUploadResponse(
        String jobId,
        String status,
        DocumentType type
) {}
