package exps.cariv.domain.customs.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 신고필증 목록 테이블 행 DTO.
 * <p>피그마 기준: 상태, 등록일, 차량번호, 차량명, 차대번호, 연식, 화주명, 선적방식, 말소등록일</p>
 */
public record CustomsListResponse(
        Long vehicleId,
        String stage,                     // VehicleStage
        String customsStatus,             // CustomsStatus: WAITING / IN_PROGRESS / DONE
        String customsStatusLabel,        // 한글 라벨: 대기 / 진행 / 완료
        Instant createdAt,
        String vehicleNo,
        String modelName,
        String vin,
        Integer modelYear,
        String shipperName,
        String shippingMethod,            // RORO / CONTAINER / null
        LocalDate deRegistrationDate,     // 말소등록일
        Long customsRequestId,            // 연결된 전송 요청 ID (null 이면 미신고)
        String customsRequestStatus,      // 내부 상태: DRAFT / SUBMITTED / PROCESSING / COMPLETED
        boolean canResend                // 재전송 버튼 활성화 여부
) {}
