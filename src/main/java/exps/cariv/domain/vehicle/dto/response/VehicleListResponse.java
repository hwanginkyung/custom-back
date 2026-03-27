package exps.cariv.domain.vehicle.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 차량 목록 화면 — 테이블 한 행.
 */
public record VehicleListResponse(
        Long id,
        String stage,                        // VehicleStage name
        Instant createdAt,                   // 등록일
        String vehicleNo,                    // 차량번호
        String modelName,                    // 차량명
        String vin,                          // 차대번호
        Integer modelYear,                   // 연식
        String carType,                      // 차종
        String shipperName,                  // 화주명
        String ownerType,                    // 소유자유형
        String ownerName,                    // 소유자명
        LocalDate purchaseDate,              // 매입일
        Long purchasePrice,                  // 매입가
        Boolean refundApplied,               // 환급 신청 여부
        String purchaseCompanyName,          // 매입처
        LocalDate licenseDate                // 면허일
) {}
