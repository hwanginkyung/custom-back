package exps.cariv.domain.login.service;


import exps.cariv.domain.login.dto.request.LoginRequest;
import exps.cariv.domain.login.dto.request.SignupRequest;
import exps.cariv.domain.login.dto.response.TokenIssueResult;
import exps.cariv.domain.login.entity.Company;
import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import exps.cariv.domain.login.repository.CompanyRepository;
import exps.cariv.domain.login.repository.UserRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.jwt.JwtTokenProvider;
import exps.cariv.global.jwt.service.RefreshTokenService;
import exps.cariv.global.jwt.service.RefreshTokenService.RefreshTokenInfo;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final LoginSecurityService loginSecurityService;

    private static final String INVALID_LOGIN_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";
    // user 미존재 시 타이밍 편차 완화를 위한 더미 해시(문자열 자체는 의미 없음)
    private static final String DUMMY_BCRYPT_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiDqJ4Yx5lL6A5B7Q2Y8xU5m8Q9nS5e";

    public void signup(SignupRequest req) {

        Company company = companyRepository.findById(req.getCompanyId())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 사용 중인 이메일입니다.");
        }
        User user = User.builder()
                .loginId(email)
                .email(email)
                .passwordHash(encoder.encode(req.getPassword()))
                .active(true)
                .role(Role.STAFF)
                .build();

        // 중요: companyId 세팅
        user.setCompanyId(company.getId());

        userRepository.save(user);
    }

    public TokenIssueResult login(LoginRequest req, String clientIp) {
        String email = req.getEmail() == null ? "" : req.getEmail().trim().toLowerCase();
        loginSecurityService.assertAllowed(email, clientIp);

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            // user enumeration 방지를 위해 동일한 password verify 비용 수행
            encoder.matches(req.getPassword(), DUMMY_BCRYPT_HASH);
            loginSecurityService.onFailure(email, clientIp);
            log.warn("[Auth] login failed ip={}, email={}, reason=invalid_credentials", clientIp, email);
            throw new CustomException(ErrorCode.UNAUTHORIZED, INVALID_LOGIN_MESSAGE);
        }

        if (!encoder.matches(req.getPassword(), user.getPasswordHash())) {
            loginSecurityService.onFailure(email, clientIp);
            log.warn("[Auth] login failed ip={}, email={}, reason=invalid_credentials", clientIp, email);
            throw new CustomException(ErrorCode.UNAUTHORIZED, INVALID_LOGIN_MESSAGE);
        }

        if (!user.isActive()) {
            loginSecurityService.onFailure(email, clientIp);
            log.warn("[Auth] login failed ip={}, email={}, reason=account_inactive", clientIp, email);
            throw new CustomException(ErrorCode.UNAUTHORIZED, INVALID_LOGIN_MESSAGE);
        }

        String access = jwtProvider.createAccessToken(user);
        String refresh = jwtProvider.createRefreshToken(user);

        Claims claims = jwtProvider.getClaims(refresh);
        refreshTokenService.saveNewToken(
                refresh,
                user.getId(),
                user.getLoginId(),
                claims.getExpiration().toInstant()
        );
        loginSecurityService.onSuccess(email, clientIp);
        log.info("[Auth] login success userId={}, companyId={}, ip={}", user.getId(), user.getCompanyId(), clientIp);

        return TokenIssueResult.of(access, refresh);
    }

    public TokenIssueResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        Claims tokenClaims = jwtProvider.validateAndGetClaims(refreshToken);
        if (!jwtProvider.isTokenType(tokenClaims, "refresh")) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        String subject = tokenClaims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        // Redis에서 토큰 검증 → userId/loginId 가져오기
        RefreshTokenInfo info = refreshTokenService.validate(refreshToken)
                .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));
        if (!Objects.equals(info.loginId(), subject)) {
            refreshTokenService.revoke(refreshToken);
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        // loginId로 User 조회 (Redis @Cacheable 캐시 적중)
        User user = userRepository.findByLoginId(info.loginId())
                .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));
        Long tokenUserId = jwtProvider.extractUserId(tokenClaims);
        if (tokenUserId != null && !Objects.equals(tokenUserId, user.getId())) {
            refreshTokenService.revoke(refreshToken);
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        Long tokenTenantId = jwtProvider.extractTenantId(tokenClaims);
        if (tokenTenantId != null && !Objects.equals(tokenTenantId, user.getCompanyId())) {
            refreshTokenService.revoke(refreshToken);
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        if (!user.isActive()) {
            refreshTokenService.revoke(refreshToken);
            throw new CustomException(ErrorCode.ACCOUNT_DISABLED);
        }

        String newAccess = jwtProvider.createAccessToken(user);
        String newRefresh = jwtProvider.createRefreshToken(user);

        // 기존 토큰 삭제 (Redis DEL — 즉시)
        refreshTokenService.revoke(refreshToken);

        // 새 토큰 저장
        Claims claims = jwtProvider.getClaims(newRefresh);
        refreshTokenService.saveNewToken(
                newRefresh,
                user.getId(),
                user.getLoginId(),
                claims.getExpiration().toInstant()
        );
        log.info("[Auth] refresh success userId={}, companyId={}", user.getId(), user.getCompanyId());

        return TokenIssueResult.of(newAccess, newRefresh);
    }

    public void logout(Long currentUserId, String currentLoginId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        Claims tokenClaims = jwtProvider.validateAndGetClaims(refreshToken);
        if (!jwtProvider.isTokenType(tokenClaims, "refresh")) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        RefreshTokenInfo info = refreshTokenService.validate(refreshToken).orElse(null);

        // 이미 만료/폐기된 토큰은 로그아웃 완료로 간주 (idempotent)
        if (info == null) {
            log.info("[Auth] logout idempotent userId={}, loginId={}", currentUserId, currentLoginId);
            return;
        }

        if (!Objects.equals(info.userId(), currentUserId)
                || !Objects.equals(info.loginId(), currentLoginId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        refreshTokenService.revoke(refreshToken);
        log.info("[Auth] logout success userId={}, loginId={}", currentUserId, currentLoginId);
    }

}
