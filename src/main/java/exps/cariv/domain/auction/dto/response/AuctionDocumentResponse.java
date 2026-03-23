package exps.cariv.domain.auction.dto.response;

import exps.cariv.domain.auction.dto.AuctionSnapshot;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;

import java.time.Instant;

public record AuctionDocumentResponse(
        Long id,
        String s3Key,
        String originalFilename,
        Instant uploadedAt,
        Instant parsedAt,
        AuctionSnapshot snapshot,
        OcrFieldResult ocrResult
) {}
