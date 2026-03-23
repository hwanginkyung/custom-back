package exps.cariv.global.logging;

import exps.cariv.global.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 단위 MDC(Mapped Diagnostic Context) 설정 필터.
 *
 * JwtAuthenticationFilter 이후에 실행되어 아래 정보를 MDC에 주입:
 * - requestId : 요청별 고유 UUID (Correlation ID)
 * - companyId : TenantContext에서 추출한 현재 테넌트
 * - userId    : SecurityContext의 principal name (loginId)
 * - clientIp  : 클라이언트 IP (프록시 헤더 고려)
 * - method    : HTTP 메서드
 * - uri       : 요청 URI
 *
 * 로그 패턴에서 %X{requestId}, %X{companyId} 등으로 참조 가능.
 */
public class MdcFilter extends OncePerRequestFilter {

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String REQUEST_ID = "requestId";
    private static final String COMPANY_ID = "companyId";
    private static final String USER_ID    = "userId";
    private static final String CLIENT_IP  = "clientIp";
    private static final String METHOD     = "method";
    private static final String URI        = "uri";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 1) Correlation ID: 클라이언트가 보낸 X-Request-Id가 있으면 재사용
            String requestId = resolveRequestId(request.getHeader("X-Request-Id"));
            MDC.put(REQUEST_ID, requestId);

            // 2) 테넌트 정보 (JwtAuthenticationFilter가 이미 설정)
            Long companyId = TenantContext.getCompanyId();
            if (companyId != null) {
                MDC.put(COMPANY_ID, companyId.toString());
            }

            // 3) 사용자 정보 (SecurityContext에서 추출)
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                MDC.put(USER_ID, auth.getName());
            }

            // 4) 요청 메타
            MDC.put(CLIENT_IP, resolveClientIp(request));
            MDC.put(METHOD, request.getMethod());
            MDC.put(URI, request.getRequestURI());

            // 응답 헤더에도 requestId 포함 (프론트엔드 디버깅용)
            response.setHeader("X-Request-Id", requestId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String resolveRequestId(String headerValue) {
        if (headerValue == null) {
            return newRequestId();
        }
        String candidate = headerValue.trim();
        if (!REQUEST_ID_PATTERN.matcher(candidate).matches()) {
            return newRequestId();
        }
        return candidate;
    }

    private String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 첫 번째 IP가 원래 클라이언트
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
