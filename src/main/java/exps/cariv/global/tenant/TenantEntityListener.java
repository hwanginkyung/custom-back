
package exps.cariv.global.tenant;


import exps.cariv.global.common.TenantEntity;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

public class TenantEntityListener {

    @PrePersist
    public void prePersist(TenantEntity e) {
        if (e.getCompanyId() == null) {
            Long cid = TenantContext.getCompanyId();
            if (cid == null) throw new CustomException(ErrorCode.UNAUTHORIZED);
            e.setCompanyId(cid);
        }
    }

    @PreUpdate
    public void preUpdate(TenantEntity e) {
        // 다른 테넌트로 바꿔치기 방지
        Long cid = TenantContext.getCompanyId();
        if (cid != null && e.getCompanyId() != null && !e.getCompanyId().equals(cid)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }
    }
}

