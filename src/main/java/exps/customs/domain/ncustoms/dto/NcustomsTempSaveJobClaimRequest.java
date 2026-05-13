package exps.customs.domain.ncustoms.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NcustomsTempSaveJobClaimRequest {

    @NotNull
    private Long companyId;

    @Min(1)
    @Max(20)
    private Integer limit = 1;

    private String workerId;
}
