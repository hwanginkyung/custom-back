package exps.cariv.domain.vehicle.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/vehicle/detail/{vehicleId} — 차량 상세 응답.
 */
public record VehicleDetailResponse(
        Long id,
        String stage,
        Instant createdAt,

        // 차량 기본정보
        String vin,
        String vehicleNo,
        String carType,
        String vehicleUse,
        String modelName,
        String engineType,
        Long mileageKm,
        String ownerName,
        String ownerId,
        Integer modelYear,
        String ownerType,
        String manufactureYearMonth,
        Integer displacement,
        LocalDate firstRegistrationDate,
        String transmission,
        String fuelType,
        String color,
        Integer weight,
        Integer seatingCapacity,
        Integer length,
        Integer height,
        Integer width,

        // 화주
        String shipperName,
        Long shipperId,

        // 매입정보
        Long purchasePrice,
        LocalDate purchaseDate,

        // 매출정보
        Long saleAmount,
        LocalDate saleDate,

        // 첨부파일
        List<DocumentInfo> documents,
        List<AttachmentSection> attachmentSections
) {
    public record DocumentInfo(
            Long id,
            String type,         // DocumentType name
            String status,       // DocumentStatus name
            String s3Key,
            String originalFilename,
            Instant uploadedAt
    ) {}

    public record AttachmentSection(
            String sectionKey,
            String sectionName,
            List<AttachmentSlot> slots
    ) {}

    public record AttachmentSlot(
            String slotKey,
            String slotName,
            boolean available,
            Long documentId,
            String documentType,
            String status,
            String s3Key,
            String previewUrl,
            String downloadUrl,
            String originalFilename,
            Long sizeBytes,
            Instant uploadedAt,
            String contentType
    ) {}
}
