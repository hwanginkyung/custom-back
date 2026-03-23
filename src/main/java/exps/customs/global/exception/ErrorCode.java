package exps.customs.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "잘못된 요청입니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "요청 횟수가 너무 많습니다. 잠시 후 다시 시도해주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "유저를 찾을 수 없습니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.BAD_REQUEST, "DUPLICATE_LOGIN_ID", "로그인 ID가 이미 존재합니다."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "비활성화된 계정입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD", "비밀번호가 일치하지 않습니다"),

    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "유효하지 않은 토큰입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "RT_EXPIRED", "Refresh Token이 만료되었습니다."),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "RT_REVOKED", "Refresh Token이 폐기되었습니다."),

    TENANT_MISMATCH(HttpStatus.FORBIDDEN, "TENANT_MISMATCH", "회사 데이터 접근 권한이 없습니다."),
    CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "케이스를 찾을 수 없습니다."),
    CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "화주를 찾을 수 없습니다."),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
