package exps.customs.global.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.customs.domain.login.entity.enumType.Role;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import exps.customs.global.exception.ErrorResponse;
import exps.customs.global.security.CustomUserDetails;
import exps.customs.global.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean isPublicAuthEndpoint =
                path.equals("/api/auth/login")
                        || path.equals("/api/auth/refresh")
                        || path.equals("/api/auth/signup");
        return isPublicAuthEndpoint
                || path.equals("/") || path.equals("/error") || path.equals("/favicon.ico")
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")
                || path.startsWith("/swagger-resources") || path.startsWith("/webjars")
                || path.startsWith("/h2-console");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, java.io.IOException {
        String token = resolveToken(request);

        if (token != null) {
            try {
                Claims claims = jwtTokenProvider.validateAndGetClaims(token);
                if (!jwtTokenProvider.isTokenType(claims, "access")) {
                    throw new CustomException(ErrorCode.TOKEN_INVALID);
                }
                Long tenantId = jwtTokenProvider.extractTenantId(claims);
                if (tenantId == null || tenantId <= 0L) {
                    throw new CustomException(ErrorCode.TOKEN_INVALID, "토큰에 tenant 정보가 없습니다.");
                }
                var authentication = jwtTokenProvider.getAuthentication(claims);
                Object principal = authentication.getPrincipal();
                if (principal instanceof CustomUserDetails cud) {
                    if (cud.getRole() != Role.MASTER && !tenantId.equals(cud.getCompanyId())) {
                        throw new CustomException(ErrorCode.TOKEN_INVALID, "토큰 tenant 정보가 사용자 정보와 일치하지 않습니다.");
                    }
                }
                SecurityContextHolder.getContext().setAuthentication(authentication);
                TenantContext.setCompanyId(tenantId);
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.TOKEN_INVALID || e.getErrorCode() == ErrorCode.TOKEN_EXPIRED) {
                    log.warn("[JwtFilter] 토큰 인증 실패: {}", e.getMessage());
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                    sendErrorResponse(response, e.getErrorCode());
                    return;
                }
                throw e;
            } catch (JwtException | IllegalArgumentException e) {
                log.warn("[JwtFilter] 토큰 인증 실패: {}", e.getMessage());
                TenantContext.clear();
                SecurityContextHolder.clearContext();
                sendErrorResponse(response, ErrorCode.TOKEN_INVALID);
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) return bearer.substring(7);
        return null;
    }

    private void sendErrorResponse(HttpServletResponse response, ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .status(errorCode.getStatus().value())
                .timestamp(LocalDateTime.now().toString())
                .build();
        objectMapper.writeValue(response.getWriter(), body);
    }
}
