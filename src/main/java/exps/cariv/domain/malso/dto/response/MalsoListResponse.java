package exps.cariv.domain.malso.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 말소 목록 테이블 행 DTO.
 */
public record MalsoListResponse(
        Long vehicleId,
        String stage,           // VehicleStage (원래 lifecycle 단계)
        String malsoStatus,     // MalsoStatus: WAITING / IN_PROGRESS / DONE
        String malsoStatusLabel,// 한글 라벨: 대기 / 진행 / 완료
        Instant createdAt,
        String vehicleNo,
        String modelName,
        String vin,
        Integer modelYear,
        String carType,
        String shipperName,
        String ownerType,
        boolean hasDeregistrationDoc,
        LocalDate deRegistrationDate   // 말소등록일 (Figma 목록 테이블)
) {}
