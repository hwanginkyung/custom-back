package exps.customs.domain.integration.cariv.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Cariv -> Customs 연동 요청 생성")
public class CarivBrokerConnectionRequest {

    @NotNull
    @Schema(description = "수출자 회사 ID", example = "12")
    private Long exporterCompanyId;

    @NotBlank
    @Schema(description = "수출자 회사명", example = "진솔무역")
    private String exporterCompanyName;

    @Schema(description = "수출자 사업자번호", example = "123-45-67890")
    private String exporterBusinessNumber;

    @NotNull
    @Schema(description = "관세사 회사 ID", example = "1")
    private Long brokerCompanyId;
}
