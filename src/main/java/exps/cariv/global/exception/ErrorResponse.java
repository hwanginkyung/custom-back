package exps.cariv.global.exception;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ErrorResponse {

    private final String code;
    private final String message;
    private final int status;
    private final String timestamp;
}

