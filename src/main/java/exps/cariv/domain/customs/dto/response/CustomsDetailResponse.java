package exps.cariv.domain.customs.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 신고필증 차량 상세 응답.
 */
public record CustomsDetailResponse(
        Long vehicleId,
        String stage,
        Instant createdAt,

        // 차량 기본 정보
        String vin,
        String vehicleNo,
        String carType,
        String modelName,
        Integer modelYear,
        String shipperName,
        String shippingMethod,
        LocalDate deRegistrationDate,

        // 신고필증 상태
        String customsStatus,           // CustomsStatus: WAITING / IN_PROGRESS / DONE
        String customsStatusLabel,      // 한글 라벨: 대기 / 진행 / 완료

        // 관세사 전송 정보 (없으면 null)
        CustomsRequestInfo customsRequest,

        // 문서 목록
        List<DocInfo> documents
) {

    public record CustomsRequestInfo(
            Long requestId,
            String status,               // UI 상태: WAITING / IN_PROGRESS / DONE
            String requestStatus,        // 내부 상태: DRAFT / SUBMITTED / PROCESSING / COMPLETED
            boolean canResend,           // 재전송 버튼 활성화 여부
            String customsBrokerName,
            String shippingMethod,
            List<VehicleItemInfo> vehicles,
            ContainerInfoResponse containerInfo,
            List<String> containerPhotoS3Keys
    ) {}

    public record VehicleItemInfo(
            Long itemId,
            Long vehicleId,
            Long price,
            String tradeCondition,
            Long shippingFee,
            Long insuranceFee,
            Long otherFee,
            String vehiclePhoto1S3Key,
            String vehiclePhoto2S3Key,
            String vehiclePhoto3S3Key,
            String vehiclePhoto4S3Key
    ) {}

    public record ContainerInfoResponse(
            String containerNo,
            String sealNo,
            String entryPort,
            String warehouseLocation,
            String vesselName,
            String exportPort,
            String destinationCountry,
            String consignee
    ) {}

    public record DocInfo(
            Long documentId,
            String type,
            String status,
            String s3Key,
            String originalFilename,
            Long sizeBytes,
            Instant uploadedAt
    ) {}
}
