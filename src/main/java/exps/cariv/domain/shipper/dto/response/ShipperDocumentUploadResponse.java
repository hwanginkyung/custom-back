package exps.cariv.domain.shipper.dto.response;

public record ShipperDocumentUploadResponse(
        Long documentId,
        String s3Key,
        String type
) {}
