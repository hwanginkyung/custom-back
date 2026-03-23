package exps.customs.global.tenant;

import exps.customs.global.common.TenantEntity;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
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
        Long cid = TenantContext.getCompanyId();
        if (cid != null && e.getCompanyId() != null && !e.getCompanyId().equals(cid)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }
    }
}
