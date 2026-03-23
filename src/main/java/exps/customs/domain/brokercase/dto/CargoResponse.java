package exps.customs.domain.brokercase.dto;

import exps.customs.domain.brokercase.entity.CaseCargo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@Schema(description = "화물 응답 DTO")
public class CargoResponse {

    private final Long id;
    private final String itemName;
    private final String hsCode;
    private final BigDecimal quantity;
    private final String unit;
    private final BigDecimal unitPrice;
    private final BigDecimal totalPrice;
    private final BigDecimal weight;
    private final String originCountry;

    public static CargoResponse from(CaseCargo cargo) {
        return CargoResponse.builder()
                .id(cargo.getId())
                .itemName(cargo.getItemName())
                .hsCode(cargo.getHsCode())
                .quantity(cargo.getQuantity())
                .unit(cargo.getUnit())
                .unitPrice(cargo.getUnitPrice())
                .totalPrice(cargo.getTotalPrice())
                .weight(cargo.getWeight())
                .originCountry(cargo.getOriginCountry())
                .build();
    }
}
