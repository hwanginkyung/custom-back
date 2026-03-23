package exps.customs.domain.login.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(staticName = "of")
public class TokenResponse {
    private final String accessToken;
}
