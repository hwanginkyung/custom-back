package exps.cariv.global.tenant.aspect;

import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilterAspect {

    private final EntityManager em;

    // 중첩 호출 안전장치
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    @Around("@annotation(exps.cariv.global.tenant.aspect.TenantFiltered) || @within(exps.cariv.global.tenant.aspect.TenantFiltered)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {

        Long companyId = TenantContext.getCompanyId();
        if (companyId == null) {
            String target = pjp.getTarget().getClass().getSimpleName();
            String method = pjp.getSignature().getName();
            log.warn("[TenantFilter] companyId 미설정 - {}.{}()", target, method);
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "테넌트 컨텍스트가 설정되지 않았습니다: " + target + "." + method);
        }

        Session session = em.unwrap(Session.class);

        int depth = DEPTH.get();
        boolean enabledHere = false;

        // depth==0일 때만 enable
        if (depth == 0) {
            Filter filter = session.enableFilter("tenantFilter");
            filter.setParameter("companyId", companyId);
            enabledHere = true;
        }

        DEPTH.set(depth + 1);

        try {
            return pjp.proceed();
        } finally {
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
                //  enable을 이 around에서 했던 경우만 disable
                if (enabledHere) {
                    session.disableFilter("tenantFilter");
                }
            } else {
                DEPTH.set(next);
            }
        }
    }
}
