package exps.cariv.domain.login.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "로그아웃 요청 DTO")
public class LogoutRequest {

    @Schema(description = "리프레시 토큰", example = "eyJhbGciOi...")
    private final String refreshToken;

    @JsonCreator
    public LogoutRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
