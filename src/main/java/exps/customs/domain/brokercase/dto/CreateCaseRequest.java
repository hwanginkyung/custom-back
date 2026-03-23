package exps.customs.domain.brokercase.dto;

import exps.customs.domain.brokercase.entity.ShippingMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@Schema(description = "케이스 생성 요청")
public class CreateCaseRequest {

    @NotBlank(message = "케이스 번호는 필수입니다.")
    @Schema(description = "케이스 번호", example = "CASE-2026-0001")
    private String caseNumber;

    @NotNull(message = "화주 ID는 필수입니다.")
    @Schema(description = "화주 ID")
    private Long clientId;

    @Schema(description = "운송 방법")
    private ShippingMethod shippingMethod;

    @Schema(description = "B/L 번호")
    private String blNumber;

    @Schema(description = "입항 예정일")
    private LocalDate etaDate;

    @Schema(description = "출발항")
    private String departurePorts;

    @Schema(description = "도착항")
    private String arrivalPort;

    @Schema(description = "총 금액")
    private BigDecimal totalAmount;

    @Schema(description = "메모")
    private String memo;

    @Schema(description = "담당자 ID")
    private Long assigneeId;
}
