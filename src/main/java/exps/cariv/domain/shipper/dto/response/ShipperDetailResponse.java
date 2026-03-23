package exps.cariv.domain.shipper.dto.response;

import java.time.Instant;
import java.util.List;

public record ShipperDetailResponse(
        Long shipperId,
        String name,
        String shipperType,
        String phone,
        String address,
        boolean active,
        List<ShipperDocInfo> documents
) {
    public record ShipperDocInfo(
            Long documentId,
            String type,
            String s3Key,
            String originalFilename,
            Long sizeBytes,
            Instant uploadedAt
    ) {}
}
