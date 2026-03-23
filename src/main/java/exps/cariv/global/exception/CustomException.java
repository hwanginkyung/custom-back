package exps.cariv.global.exception;


import lombok.Getter;

@Getter
public class CustomException extends BusinessException {

    public CustomException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 구체적 에러 메시지를 같이 전달할 때 사용.
     * ex) throw new CustomException(ErrorCode.INVALID_INPUT, "휴대전화 번호 형식이 올바르지 않습니다.")
     */
    public CustomException(ErrorCode errorCode, String detailMessage) {
        super(errorCode, detailMessage);
    }
}
