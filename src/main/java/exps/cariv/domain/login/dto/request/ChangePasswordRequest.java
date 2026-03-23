package exps.cariv.domain.login.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "관리자 비밀번호 변경 요청 DTO")
public class ChangePasswordRequest {

    @Schema(description = "기존 비밀번호", example = "oldPassword")
    @NotBlank(message = "기존 비밀번호는 필수입니다.")
    private final String oldPassword;

    @Schema(description = "새 비밀번호", example = "newPassword")
    @NotBlank(message = "새 비밀번호는 필수입니다.")
    private final String newPassword;

    @Schema(description = "새 비밀번호 확인", example = "newPassword")
    @NotBlank(message = "새 비밀번호 확인은 필수입니다.")
    private final String newCheckPassword;

    @JsonCreator
    public ChangePasswordRequest(String oldPassword, String newPassword, String newCheckPassword) {
        this.oldPassword = oldPassword;
        this.newPassword = newPassword;
        this.newCheckPassword = newCheckPassword;
    }
}
