package exps.customs.domain.login.dto.request;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateMyNcustomsProfileRequest {

    @Pattern(regexp = "^$|[A-Za-z0-9]{1,10}", message = "ncustomsUserCode must be 1~10 alphanumeric chars")
    private String ncustomsUserCode;

    @Pattern(regexp = "^$|.{1,100}", message = "ncustomsWriterId max length is 100")
    private String ncustomsWriterId;

    @Pattern(regexp = "^$|.{1,100}", message = "ncustomsWriterName max length is 100")
    private String ncustomsWriterName;
}
