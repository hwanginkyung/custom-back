package exps.cariv.domain.login.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
@Schema(description = "회원가입 요청 DTO")
public class SignupRequest {

    @Schema(description = "회사 ID", example = "1")
    @NotNull(message = "회사 ID는 필수입니다.")
    private final Long companyId;

    @Schema(description = "이메일", example = "staff@cariv.local")
    @Email(message = "유효한 이메일 형식이 아닙니다.")
    @NotBlank(message = "이메일은 필수입니다.")
    private final String email;

    @Schema(description = "비밀번호", example = "P@ssw0rd")
    @NotBlank(message = "비밀번호는 필수입니다.")
    private final String password;

    @JsonCreator
    public SignupRequest(Long companyId, String email, String password) {
        this.companyId = companyId;
        this.email = email;
        this.password = password;
    }
}
