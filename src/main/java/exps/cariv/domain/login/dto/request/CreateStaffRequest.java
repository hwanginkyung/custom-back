package exps.cariv.domain.login.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "직원 생성 요청 DTO")
public class CreateStaffRequest {

    @Schema(description = "직원 이메일", example = "staff@cariv.local")
    @Email(message = "유효한 이메일 형식이 아닙니다.")
    @NotBlank(message = "이메일은 필수입니다.")
    private final String email;

    @Schema(description = "직원 초기 비밀번호", example = "P@ssw0rd")
    @NotBlank(message = "비밀번호는 필수입니다.")
    private final String password;

    @JsonCreator
    public CreateStaffRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }
}
