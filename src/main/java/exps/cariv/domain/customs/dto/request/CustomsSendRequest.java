package exps.cariv.domain.customs.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 관세사에게 전송 요청.
 * <p>선적방식에 따라 containerInfo / 사진 유무가 달라진다.</p>
 * <p>차량 사진은 차량별(최대 4장), 컨테이너 사진은 요청 공통(최대 3장).</p>
 */
public record CustomsSendRequest(
        @NotBlank(message = "선적방식은 필수입니다.")
        String shippingMethod,           // RORO / CONTAINER
        Long customsBrokerId,            // 선택값
        String customsBrokerName,

        @NotEmpty(message = "차량 목록은 1대 이상 필요합니다.")
        @Valid
        List<VehicleItem> vehicles,

        // Container 전용
        ContainerInfoRequest containerInfo,

        // Container 전용 — 요청 공통 컨테이너 사진 (최대 3장)
        @Size(max = 3, message = "컨테이너 사진은 최대 3장까지 가능합니다.")
        List<String> containerPhotoS3Keys
) {

    /**
     * 차량별 정보 — 금액/거래조건 + 사진 S3 key.
     * <p>RORO: 사진 없음, Container: 차량사진 최대 4장.</p>
     */
    public record VehicleItem(
            @NotNull(message = "차량 ID는 필수입니다.")
            Long vehicleId,
            Long price,
            String tradeCondition,         // FOB / CIF / CFR
            Long shippingFee,              // 운임료 (CIF, CFR)
            Long insuranceFee,             // 보험료 (CIF)
            Long otherFee,                 // 기타금액 (CIF)

            // Container 전용 — 차량 사진 S3 key (프론트에서 미리 업로드)
            @Size(max = 4, message = "차량 사진은 차량당 최대 4장까지 가능합니다.")
            List<String> vehiclePhotoS3Keys
    ) {}

    /**
     * 컨테이너 정보 (CONTAINER 전용).
     * <p>피그마: 컨넘버, 씰넘버, 반입지, 쇼핑(선명), 수출항, 목적국, Consignee</p>
     */
    public record ContainerInfoRequest(
            String containerNo,
            String sealNo,
            String entryPort,
            String warehouseLocation,
            String vesselName,
            String exportPort,
            String destinationCountry,
            String consignee
    ) {}
}
