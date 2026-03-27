package exps.cariv.domain.vehicle.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * POST /api/vehicle/upload — 차량 등록 요청.
 * 파일 업로드(reg/id-card) 후 받은 documentId들과 함께 전송.
 */
public record VehicleCreateRequest(
        // 화주
        @NotNull(message = "화주 선택은 필수입니다.")
        Long shipperId,                 // 매입처(화주) ID

        // 소유자유형
        @NotNull(message = "소유자유형은 필수입니다.")
        String ownerType,

        // 매입일자
        @NotNull(message = "매입일자는 필수입니다.")
        LocalDate purchaseDate,

        // 문서 ID들 (업로드 후 받은 ID)
        @NotNull(message = "자동차등록증 업로드는 필수입니다.")
        Long registrationDocumentId,    // 자동차등록증 document id (required)
        Long ownerIdCardDocumentId      // 소유자 신분증 document id (ownerType 조건부 필수)
) {}
