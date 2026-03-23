package exps.cariv.global.exception;

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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
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

    /**
     * ✅ message를 더 구체적으로 덮어쓰고 싶을 때 사용
     */
    private ResponseEntity<ErrorResponse> build(ErrorCode code, String overrideMessage) {
        ErrorResponse response = ErrorResponse.builder()
                .code(code.getCode())
                .message(overrideMessage != null ? overrideMessage : code.getMessage())
                .status(code.getStatus().value())
                .timestamp(LocalDateTime.now().toString())
                .build();

        return ResponseEntity.status(code.getStatus()).body(response);
    }

    /* -------------------- Custom -------------------- */

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleCustom(BusinessException ex, HttpServletRequest req) {
        log.warn("[BusinessException] path={}, code={}, msg={}",
                req.getRequestURI(), ex.getErrorCode().getCode(), ex.getMessage());
        // detailMessage가 있으면 구체적 메시지 사용, 없으면 ErrorCode 기본 메시지
        return build(ex.getErrorCode(), ex.getDetailMessage());
    }

    /* -------------------- JWT -------------------- */

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<ErrorResponse> handleExpiredJwtException(ExpiredJwtException ex) {
        return build(ErrorCode.TOKEN_EXPIRED);
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ErrorResponse> handleJwtException(JwtException ex) {
        return build(ErrorCode.TOKEN_INVALID);
    }

    /* -------------------- Auth -------------------- */

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        if (isSseRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return build(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<?> handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletRequest req) {
        if (isSseRequest(req)) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return build(ErrorCode.INTERNAL_SERVER_ERROR, "요청 시간이 초과되었습니다.");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<?> handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest req) {
        if (isSseRequest(req)) {
            if (isClientDisconnect(ex)) {
                log.debug("[SSE Disconnect] path={}, type={}, msg={}",
                        req.getRequestURI(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage());
            } else {
                log.debug("[SSE AsyncRequestNotUsable] path={}, msg={}",
                        req.getRequestURI(),
                        ex.getMessage());
            }
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        log.warn("[AsyncRequestNotUsable] path={}, msg={}", req.getRequestURI(), ex.getMessage(), ex);
        return build(ErrorCode.INTERNAL_SERVER_ERROR, "비동기 요청 처리 중 오류가 발생했습니다.");
    }

    /* -------------------- @Valid Body -------------------- */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidError(MethodArgumentNotValidException ex) {

        // field별 메시지 모으기 (ex: "documentId: must not be null, shipperName: must not be blank")
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

    /* -------------------- QueryParam validation -------------------- */

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

    /* -------------------- JSON 파싱 에러 -------------------- */

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson(HttpMessageNotReadableException ex) {

        // Jackson root cause를 파고들어서 "어느 필드가 어떤 값 때문에" 깨졌는지 출력
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
        } else if (root != null && root.getMessage() != null) {
            // 너무 길면 잘라서
            msg = "요청 본문(JSON) 형식 오류: " + truncate(root.getMessage(), 200);
        }

        return build(ErrorCode.INVALID_INPUT, msg);
    }

    private Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private String jacksonPath(List<com.fasterxml.jackson.databind.JsonMappingException.Reference> path) {
        if (path == null || path.isEmpty()) return "(unknown)";
        return path.stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private String safe(Object o) {
        if (o == null) return "null";
        String s = String.valueOf(o);
        return truncate(s, 120);
    }

    /* -------------------- 404 / 405 -------------------- */

    // ✅ 요청한 URL에 매핑된 컨트롤러가 없을 때
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        String msg = "존재하지 않는 API 입니다. method=" + ex.getHttpMethod() + ", path=" + ex.getRequestURL();
        return build(ErrorCode.INVALID_INPUT, msg);
    }

    // ✅ 메서드가 다를 때 (GET인데 POST로 호출 등)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String supported = ex.getSupportedHttpMethods().stream()
                .map(HttpMethod::name)   // ✅ 이게 제일 깔끔
                .collect(Collectors.joining(","));
        String msg = "지원하지 않는 HTTP Method 입니다. supported=[" + supported + "]";
        return build(ErrorCode.INVALID_INPUT, msg);
    }

    /* -------------------- Default -------------------- */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest req) {
        if (isSseRequest(req) && isClientDisconnect(ex)) {
            log.debug("[SSE Disconnect] path={}, type={}, msg={}",
                    req.getRequestURI(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        // ✅ 최소한 path + 예외 타입은 로그에 남겨야 원인 추적 가능
        log.error("[Exception] path={}, type={}, msg={}",
                req.getRequestURI(),
                ex.getClass().getName(),
                ex.getMessage(),
                ex);

        if (isSseRequest(req)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        String msg;
        if (includeExceptionDetails) {
            msg = "서버 오류: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " - " + truncate(ex.getMessage(), 200));
        } else {
            msg = ErrorCode.INTERNAL_SERVER_ERROR.getMessage();
        }

        return build(ErrorCode.INTERNAL_SERVER_ERROR, msg);
    }

    private boolean isSseRequest(HttpServletRequest req) {
        if (req == null) return false;
        String uri = req.getRequestURI();
        if (uri != null && uri.startsWith("/api/notifications/stream")) return true;
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private boolean isClientDisconnect(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String name = t.getClass().getName();
            if (name.contains("ClientAbortException")) return true;
            if (name.contains("ClosedChannelException")) return true;
            if (name.contains("EOFException")) return true;

            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset by peer")
                        || lower.contains("disconnected client")
                        || lower.contains("stream is closed")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
