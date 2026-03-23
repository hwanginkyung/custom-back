package exps.cariv.domain.registration.dto.response;

import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.registration.dto.RegistrationSnapshot;

import java.time.Instant;

public record RegistrationDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        RegistrationSnapshot snapshot,
        OcrFieldResult ocrResult
) {}
