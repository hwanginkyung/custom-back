package exps.customs.global.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detailMessage;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detailMessage = null;
    }

    public BusinessException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage != null ? sanitize(detailMessage) : errorCode.getMessage());
        this.errorCode = errorCode;
        this.detailMessage = detailMessage != null ? sanitize(detailMessage) : null;
    }

    private static String sanitize(String input) {
        return input == null ? null : input.replaceAll("[<>\"'&]", "");
    }
}
