package exps.customs.domain.brokercase.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@Schema(description = "화물 추가 요청")
public class CreateCargoRequest {

    @NotBlank(message = "품목명은 필수입니다.")
    @Schema(description = "품목명")
    private String itemName;

    @Schema(description = "HS 코드")
    private String hsCode;

    @Schema(description = "수량")
    private BigDecimal quantity;

    @Schema(description = "단위")
    private String unit;

    @Schema(description = "단가")
    private BigDecimal unitPrice;

    @Schema(description = "총가")
    private BigDecimal totalPrice;

    @Schema(description = "중량(kg)")
    private BigDecimal weight;

    @Schema(description = "원산지")
    private String originCountry;
}
