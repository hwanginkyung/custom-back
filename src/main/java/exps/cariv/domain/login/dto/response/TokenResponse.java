package exps.cariv.domain.login.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "토큰 응답 DTO")
public class TokenResponse {

    @Schema(description = "액세스 토큰", example = "eyJhbGciOi...")
    private final String accessToken;

    public TokenResponse(String accessToken) {
        this.accessToken = accessToken;
    }

    public static TokenResponse of(String accessToken) {
        return new TokenResponse(accessToken);
    }
}
