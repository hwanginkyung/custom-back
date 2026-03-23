package exps.customs.global.logging;

import exps.customs.global.tenant.TenantContext;
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

public class MdcFilter extends OncePerRequestFilter {

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String requestId = resolveRequestId(request.getHeader("X-Request-Id"));
            MDC.put("requestId", requestId);

            Long companyId = TenantContext.getCompanyId();
            if (companyId != null) MDC.put("companyId", companyId.toString());

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                MDC.put("userId", auth.getName());
            }

            MDC.put("clientIp", resolveClientIp(request));
            MDC.put("method", request.getMethod());
            MDC.put("uri", request.getRequestURI());

            response.setHeader("X-Request-Id", requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String resolveRequestId(String headerValue) {
        if (headerValue == null) return newRequestId();
        String candidate = headerValue.trim();
        if (!REQUEST_ID_PATTERN.matcher(candidate).matches()) return newRequestId();
        return candidate;
    }

    private String newRequestId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }
}
