package exps.cariv.domain.vehicle.dto.request;

import java.time.LocalDate;

/**
 * GET /api/vehicle/management 필터 파라미터.
 */
public record VehicleListRequest(
        String stage,                        // VehicleStage name or null(=전체)
        String keyword,                      // 차량번호/차대번호/차명 검색
        String shipperName,                  // 화주명 필터
        LocalDate startDate,                 // from (등록일 시작)
        LocalDate endDate,                   // to (등록일 끝)
        LocalDate purchaseFrom,              // 매입일 시작
        LocalDate purchaseTo,                // 매입일 끝
        int page,                            // 0-based
        int size                             // default 10
) {
    public VehicleListRequest {
        if (size <= 0) size = 10;
        if (size > 100) size = 100;
        if (page < 0) page = 0;
    }

    /** purchaseMonth 없이 호출하는 기존 호환용 */
    public VehicleListRequest(String stage, String keyword, String shipperName,
                              LocalDate startDate, LocalDate endDate,
                              int page, int size) {
        this(stage, keyword, shipperName, startDate, endDate, null, null, page, size);
    }
}
