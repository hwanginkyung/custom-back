package exps.customs.domain.ncustoms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NcustomsTempSaveJobCompleteRequest {

    @NotNull
    private Boolean success;

    private String errorMessage;

    private String workerId;

    @Valid
    private NcustomsContainerTempSaveResponse result;
}
