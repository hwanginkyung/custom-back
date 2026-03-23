package exps.customs.domain.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "대시보드 응답 DTO")
public class DashboardResponse {

    @Schema(description = "전체 케이스 수")
    private final long totalCases;

    @Schema(description = "등록 상태 케이스 수")
    private final long registeredCases;

    @Schema(description = "진행 중 케이스 수")
    private final long inProgressCases;

    @Schema(description = "통관 신고 케이스 수")
    private final long declaredCases;

    @Schema(description = "통관 수리 케이스 수")
    private final long acceptedCases;

    @Schema(description = "반입 확인 케이스 수")
    private final long arrivalConfirmedCases;

    @Schema(description = "완료 케이스 수")
    private final long completedCases;

    @Schema(description = "취소 케이스 수")
    private final long cancelledCases;

    @Schema(description = "전체 화주 수")
    private final long totalClients;

    @Schema(description = "미결제 케이스 수")
    private final long unpaidCases;
}
