package exps.customs.domain.integration.cariv.dto;

import exps.customs.domain.broker.entity.ConnectionStatus;
import exps.customs.domain.brokercase.entity.CaseStatus;
import exps.customs.domain.brokercase.entity.PaymentStatus;
import exps.customs.domain.brokercase.entity.ShippingMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Cariv -> Customs 케이스 동기화 요청")
public class CarivSyncCaseRequest {

    @Schema(description = "Cariv 외부 케이스 번호(없으면 자동 생성)", example = "CARIV-EXP-2026-0001")
    private String externalCaseId;

    @Schema(description = "관세사 회사 ID(서버간 push 전용)", example = "1")
    private Long brokerCompanyId;

    @Schema(description = "수출자 회사 ID(없으면 임시 ID 생성)", example = "9001001")
    private Long exporterCompanyId;

    @NotBlank
    @Schema(description = "수출자 회사명", example = "진솔무역")
    private String exporterCompanyName;

    @Schema(description = "수출자 사업자번호", example = "123-45-67890")
    private String exporterBusinessNumber;

    @Schema(description = "수출자 연락처", example = "02-1234-5678")
    private String exporterPhoneNumber;

    @Schema(description = "수출자 이메일", example = "ops@jinsol.co.kr")
    private String exporterEmail;

    @Schema(description = "연동 상태", example = "APPROVED")
    private ConnectionStatus connectionStatus = ConnectionStatus.APPROVED;

    @Schema(description = "운송수단", example = "SEA")
    private ShippingMethod shippingMethod = ShippingMethod.SEA;

    @Schema(description = "케이스 상태", example = "REGISTERED")
    private CaseStatus caseStatus = CaseStatus.REGISTERED;

    @Schema(description = "결제 상태", example = "UNPAID")
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Schema(description = "B/L 번호", example = "OOLU1234567")
    private String blNumber;

    @Schema(description = "입항 예정일", example = "2026-04-24")
    private LocalDate etaDate;

    @Schema(description = "입항일", example = "2026-04-26")
    private LocalDate ataDate;

    @Schema(description = "출발항", example = "CNSHA (상해)")
    private String departurePorts;

    @Schema(description = "도착항", example = "KRINC (인천)")
    private String arrivalPort;

    @Schema(description = "총 금액", example = "55000000")
    private BigDecimal totalAmount;

    @Schema(description = "관세", example = "4200000")
    private BigDecimal dutyAmount;

    @Schema(description = "부가세", example = "5400000")
    private BigDecimal vatAmount;

    @Schema(description = "수수료", example = "450000")
    private BigDecimal brokerageFee;

    @Schema(description = "메모", example = "Cariv에서 관세사용으로 전달된 테스트 케이스")
    private String memo;

    @Valid
    @Schema(description = "화물 목록")
    private List<CarivSyncCargoRequest> cargos;

    @Valid
    @Schema(description = "첨부 목록(S3 참조 메타데이터)")
    private List<CarivSyncAttachmentRequest> attachments;
}
