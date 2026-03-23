package exps.customs.global.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${app.error.include-exception-details:false}")
    private boolean includeExceptionDetails;

    private ResponseEntity<ErrorResponse> build(ErrorCode code) {
        return build(code, null);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String overrideMessage) {
        ErrorResponse response = ErrorResponse.builder()
                .code(code.getCode())
                .message(overrideMessage != null ? overrideMessage : code.getMessage())
                .status(code.getStatus().value())
                .timestamp(LocalDateTime.now().toString())
                .build();
        return ResponseEntity.status(code.getStatus()).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleCustom(BusinessException ex, HttpServletRequest req) {
        log.warn("[BusinessException] path={}, code={}, msg={}",
                req.getRequestURI(), ex.getErrorCode().getCode(), ex.getMessage());
        return build(ex.getErrorCode(), ex.getDetailMessage());
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<ErrorResponse> handleExpiredJwt(ExpiredJwtException ex) {
        return build(ErrorCode.TOKEN_EXPIRED);
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ErrorResponse> handleJwt(JwtException ex) {
        return build(ErrorCode.TOKEN_INVALID);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidError(MethodArgumentNotValidException ex) {
        String fieldMessages = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .distinct()
                .collect(Collectors.joining(", "));
        String msg = "요청 값이 올바르지 않습니다"
                + (fieldMessages.isBlank() ? "" : " - " + fieldMessages);
        return build(ErrorCode.INVALID_INPUT, msg);
    }

    private String formatFieldError(FieldError fe) {
        String field = fe.getField();
        String reason = fe.getDefaultMessage();
        Object rejected = fe.getRejectedValue();
        if (rejected == null) return field + ": " + reason;
        return field + ": " + reason + " (rejected=" + rejected + ")";
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        String violations = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
                    String msg = v.getMessage();
                    Object invalid = v.getInvalidValue();
                    return path + ": " + msg + (invalid == null ? "" : " (rejected=" + invalid + ")");
                })
                .distinct()
                .collect(Collectors.joining(", "));
        String msg = "요청 파라미터가 올바르지 않습니다"
                + (violations.isBlank() ? "" : " - " + violations);
        return build(ErrorCode.INVALID_INPUT, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson(HttpMessageNotReadableException ex) {
        Throwable root = rootCause(ex);
        String msg = "요청 본문(JSON)을 파싱할 수 없습니다.";

        if (root instanceof InvalidFormatException ife) {
            String fieldPath = jacksonPath(ife.getPath());
            String targetType = (ife.getTargetType() != null ? ife.getTargetType().getSimpleName() : "unknown");
            Object value = ife.getValue();
            msg = String.format("필드 '%s' 값이 올바르지 않습니다. value=%s, expectedType=%s",
                    fieldPath, safe(value), targetType);
        } else if (root instanceof MismatchedInputException mie) {
            String fieldPath = jacksonPath(mie.getPath());
            String targetType = (mie.getTargetType() != null ? mie.getTargetType().getSimpleName() : "unknown");
            msg = String.format("필드 '%s' 구조/타입이 올바르지 않습니다. expectedType=%s",
                    fieldPath, targetType);
        }

        return build(ErrorCode.INVALID_INPUT, msg);
    }

    private Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    private String jacksonPath(List<com.fasterxml.jackson.databind.JsonMappingException.Reference> path) {
        if (path == null || path.isEmpty()) return "(unknown)";
        return path.stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private String safe(Object o) {
        if (o == null) return "null";
        String s = String.valueOf(o);
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        String msg = "존재하지 않는 API 입니다. method=" + ex.getHttpMethod() + ", path=" + ex.getRequestURL();
        return build(ErrorCode.INVALID_INPUT, msg);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String supported = ex.getSupportedHttpMethods().stream()
                .map(HttpMethod::name)
                .collect(Collectors.joining(","));
        String msg = "지원하지 않는 HTTP Method 입니다. supported=[" + supported + "]";
        return build(ErrorCode.INVALID_INPUT, msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest req) {
        log.error("[Exception] path={}, type={}, msg={}",
                req.getRequestURI(), ex.getClass().getName(), ex.getMessage(), ex);
        String msg = includeExceptionDetails
                ? "서버 오류: " + ex.getClass().getSimpleName()
                : ErrorCode.INTERNAL_SERVER_ERROR.getMessage();
        return build(ErrorCode.INTERNAL_SERVER_ERROR, msg);
    }
}
