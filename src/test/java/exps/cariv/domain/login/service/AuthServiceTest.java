package exps.cariv.domain.login.service;

import exps.cariv.domain.login.dto.request.LoginRequest;
import exps.cariv.domain.login.dto.response.TokenIssueResult;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private PasswordEncoder encoder;
    @Mock
    private JwtTokenProvider jwtProvider;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private LoginSecurityService loginSecurityService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginSuccessIssuesTokensAndResetsSecurityCounters() {
        User user = activeUser(1L, "admin");
        LoginRequest request = new LoginRequest("admin@cariv.local", "password");
        Claims claims = mock(Claims.class);
        Date expiry = Date.from(Instant.parse("2030-01-01T00:00:00Z"));

        when(userRepository.findByEmailIgnoreCase("admin@cariv.local")).thenReturn(Optional.of(user));
        when(encoder.matches("password", "encoded-password")).thenReturn(true);
        when(jwtProvider.createAccessToken(user)).thenReturn("access-token");
        when(jwtProvider.createRefreshToken(user)).thenReturn("refresh-token");
        when(jwtProvider.getClaims("refresh-token")).thenReturn(claims);
        when(claims.getExpiration()).thenReturn(expiry);

        TokenIssueResult result = authService.login(request, "127.0.0.1");

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token");

        verify(loginSecurityService).assertAllowed("admin@cariv.local", "127.0.0.1");
        verify(refreshTokenService).saveNewToken("refresh-token", 1L, "admin", expiry.toInstant());
        verify(loginSecurityService).onSuccess("admin@cariv.local", "127.0.0.1");
        verify(loginSecurityService, never()).onFailure(any(), any());
    }

    @Test
    void loginFailureWithWrongPasswordRecordsFailureAndThrowsUnauthorized() {
        User user = activeUser(1L, "admin");
        LoginRequest request = new LoginRequest("admin@cariv.local", "wrong");

        when(userRepository.findByEmailIgnoreCase("admin@cariv.local")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "encoded-password")).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(request, "127.0.0.1"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(loginSecurityService).assertAllowed("admin@cariv.local", "127.0.0.1");
        verify(loginSecurityService).onFailure("admin@cariv.local", "127.0.0.1");
        verify(loginSecurityService, never()).onSuccess(any(), any());
        verify(refreshTokenService, never()).saveNewToken(any(), any(), any(), any());
    }

    @Test
    void refreshSuccessRevokesOldTokenAndStoresNewToken() {
        User user = activeUser(1L, "admin");
        String oldRefresh = "old-refresh";
        String newRefresh = "new-refresh";
        Claims oldClaims = mock(Claims.class);
        Claims newClaims = mock(Claims.class);
        Date expiry = Date.from(Instant.parse("2030-01-02T00:00:00Z"));

        when(jwtProvider.validateAndGetClaims(oldRefresh)).thenReturn(oldClaims);
        when(jwtProvider.isTokenType(oldClaims, "refresh")).thenReturn(true);
        when(oldClaims.getSubject()).thenReturn("admin");
        when(jwtProvider.extractUserId(oldClaims)).thenReturn(1L);
        when(jwtProvider.extractTenantId(oldClaims)).thenReturn(100L);
        when(refreshTokenService.validate(oldRefresh)).thenReturn(Optional.of(new RefreshTokenInfo(1L, "admin")));
        when(userRepository.findByLoginId("admin")).thenReturn(Optional.of(user));
        when(jwtProvider.createAccessToken(user)).thenReturn("new-access");
        when(jwtProvider.createRefreshToken(user)).thenReturn(newRefresh);
        when(jwtProvider.getClaims(newRefresh)).thenReturn(newClaims);
        when(newClaims.getExpiration()).thenReturn(expiry);

        TokenIssueResult result = authService.refresh(oldRefresh);

        assertThat(result.getAccessToken()).isEqualTo("new-access");
        assertThat(result.getRefreshToken()).isEqualTo(newRefresh);
        verify(refreshTokenService).revoke(oldRefresh);
        verify(refreshTokenService).saveNewToken(newRefresh, 1L, "admin", expiry.toInstant());
    }

    @Test
    void logoutRevokesTokenWhenTokenOwnerMatchesCurrentUser() {
        String refreshToken = "refresh-token";
        Claims claims = mock(Claims.class);

        when(jwtProvider.validateAndGetClaims(refreshToken)).thenReturn(claims);
        when(jwtProvider.isTokenType(claims, "refresh")).thenReturn(true);
        when(refreshTokenService.validate(refreshToken)).thenReturn(Optional.of(new RefreshTokenInfo(7L, "staff")));

        authService.logout(7L, "staff", refreshToken);

        verify(refreshTokenService).revoke(refreshToken);
    }

    private User activeUser(Long userId, String loginId) {
        User user = User.builder()
                .id(userId)
                .loginId(loginId)
                .passwordHash("encoded-password")
                .active(true)
                .role(Role.ADMIN)
                .build();
        user.setCompanyId(100L);
        return user;
    }
}
