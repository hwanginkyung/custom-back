package exps.customs.domain.integration.cariv.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Cariv 케이스 화물 동기화 요청")
public class CarivSyncCargoRequest {

    @NotBlank
    @Schema(description = "품목명", example = "자동차 브레이크 패드")
    private String itemName;

    @Schema(description = "HS 코드", example = "8708.30")
    private String hsCode;

    @Schema(description = "수량", example = "1200")
    private BigDecimal quantity;

    @Schema(description = "단위", example = "EA")
    private String unit;

    @Schema(description = "단가", example = "55.5")
    private BigDecimal unitPrice;

    @Schema(description = "총액", example = "66600")
    private BigDecimal totalPrice;

    @Schema(description = "중량(kg)", example = "380.250")
    private BigDecimal weight;

    @Schema(description = "원산지", example = "CN")
    private String originCountry;
}
