package exps.customs.global.exception;

import lombok.Getter;

@Getter
public class CustomException extends BusinessException {

    public CustomException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CustomException(ErrorCode errorCode, String detailMessage) {
        super(errorCode, detailMessage);
    }
}
