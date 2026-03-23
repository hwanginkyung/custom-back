package exps.customs.domain.login.controller;

import exps.customs.domain.login.dto.request.LoginRequest;
import exps.customs.domain.login.dto.request.LogoutRequest;
import exps.customs.domain.login.dto.request.RefreshRequest;
import exps.customs.domain.login.dto.request.SignupRequest;
import exps.customs.domain.login.dto.response.TokenIssueResult;
import exps.customs.domain.login.dto.response.TokenResponse;
import exps.customs.domain.login.service.AuthService;
import exps.customs.domain.login.service.LoginSecurityService;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "인증 / 인가 API")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginSecurityService loginSecurityService;
    private static final long REFRESH_COOKIE_MAX_AGE_SEC = 14L * 24L * 60L * 60L;

    @Value("${app.security.refresh-cookie.name:refresh_token}")
    private String refreshCookieName;

    @Value("${app.security.refresh-cookie.secure:false}")
    private boolean refreshCookieSecure;

    @Value("${app.security.refresh-cookie.same-site:Lax}")
    private String refreshCookieSameSite;

    @PostMapping("/signup")
    @Operation(summary = "회원가입")
    public String signup(@Valid @RequestBody SignupRequest req, HttpServletRequest request) {
        loginSecurityService.assertSignupAllowed(extractClientIp(request));
        authService.signup(req);
        return "ok";
    }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인 → Access/Refresh 토큰 발급")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {
        TokenIssueResult tokens = authService.login(req, extractClientIp(request));
        writeRefreshCookie(response, tokens.getRefreshToken());
        return ResponseEntity.ok(TokenResponse.of(tokens.getAccessToken()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "토큰 재발급")
    public ResponseEntity<TokenResponse> refresh(
            @RequestBody(required = false) RefreshRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {
        loginSecurityService.assertRefreshAllowed(extractClientIp(request));
        String refreshToken = resolveRefreshToken(req, request);
        TokenIssueResult tokens = authService.refresh(refreshToken);
        writeRefreshCookie(response, tokens.getRefreshToken());
        return ResponseEntity.ok(TokenResponse.of(tokens.getAccessToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃")
    public ResponseEntity<String> logout(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestBody(required = false) LogoutRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {
        String refreshToken = resolveRefreshToken(req, request);
        authService.logout(me.getUserId(), me.getUsername(), refreshToken);
        clearRefreshCookie(response);
        return ResponseEntity.ok("ok");
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true).secure(refreshCookieSecure).path("/api/auth")
                .sameSite(refreshCookieSameSite).maxAge(REFRESH_COOKIE_MAX_AGE_SEC).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true).secure(refreshCookieSecure).path("/api/auth")
                .sameSite(refreshCookieSameSite).maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String resolveRefreshToken(RefreshRequest req, HttpServletRequest request) {
        String bodyToken = req == null ? null : req.getRefreshToken();
        if (bodyToken != null && !bodyToken.isBlank()) return bodyToken;
        return resolveRefreshTokenFromCookie(request);
    }

    private String resolveRefreshToken(LogoutRequest req, HttpServletRequest request) {
        String bodyToken = req == null ? null : req.getRefreshToken();
        if (bodyToken != null && !bodyToken.isBlank()) return bodyToken;
        return resolveRefreshTokenFromCookie(request);
    }

    private String resolveRefreshTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) throw new CustomException(ErrorCode.TOKEN_INVALID);
        for (Cookie cookie : cookies) {
            if (refreshCookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        throw new CustomException(ErrorCode.TOKEN_INVALID);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) return xForwardedFor.split(",")[0].trim();
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) return xRealIp.trim();
        return request.getRemoteAddr();
    }
}
