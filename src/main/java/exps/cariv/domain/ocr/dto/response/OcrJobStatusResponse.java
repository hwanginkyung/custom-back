package exps.cariv.domain.ocr.dto.response;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.entity.OcrJobStatus;

import java.time.Instant;

public record OcrJobStatusResponse(
        Long jobId,
        DocumentType documentType,
        OcrJobStatus status,
        Long vehicleId,
        Long documentId,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        OcrFieldResult result
) {}
