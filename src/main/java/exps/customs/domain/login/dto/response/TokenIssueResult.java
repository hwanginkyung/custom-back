package exps.customs.domain.login.dto.response;

import lombok.Getter;

@Getter
public class TokenIssueResult {

    private final String accessToken;
    private final String refreshToken;

    public TokenIssueResult(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public static TokenIssueResult of(String accessToken, String refreshToken) {
        return new TokenIssueResult(accessToken, refreshToken);
    }
}
