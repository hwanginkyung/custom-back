package exps.customs.domain.brokercase.dto;

import exps.customs.domain.brokercase.entity.CaseStatus;
import exps.customs.domain.brokercase.entity.PaymentStatus;
import exps.customs.domain.brokercase.entity.ShippingMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@Schema(description = "케이스 수정 요청")
public class UpdateCaseRequest {

    @Schema(description = "상태")
    private CaseStatus status;

    @Schema(description = "결제 상태")
    private PaymentStatus paymentStatus;

    @Schema(description = "운송 방법")
    private ShippingMethod shippingMethod;

    @Schema(description = "B/L 번호")
    private String blNumber;

    @Schema(description = "입항 예정일")
    private LocalDate etaDate;

    @Schema(description = "실제 입항일")
    private LocalDate ataDate;

    @Schema(description = "통관일")
    private LocalDate customsDate;

    @Schema(description = "반출일")
    private LocalDate releaseDate;

    @Schema(description = "출발항")
    private String departurePorts;

    @Schema(description = "도착항")
    private String arrivalPort;

    @Schema(description = "총 금액")
    private BigDecimal totalAmount;

    @Schema(description = "관세")
    private BigDecimal dutyAmount;

    @Schema(description = "부가세")
    private BigDecimal vatAmount;

    @Schema(description = "중개 수수료")
    private BigDecimal brokerageFee;

    @Schema(description = "메모")
    private String memo;

    @Schema(description = "담당자 ID")
    private Long assigneeId;
}
