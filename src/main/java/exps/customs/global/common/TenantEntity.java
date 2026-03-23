package exps.customs.global.common;

import exps.customs.global.tenant.TenantEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@FilterDef(
        name = "tenantFilter",
        parameters = @ParamDef(name = "companyId", type = Long.class)
)
@Filter(name = "tenantFilter", condition = "company_id = :companyId")
@MappedSuperclass
@Getter
@EntityListeners(TenantEntityListener.class)
public abstract class TenantEntity extends BaseEntity {

    @Column(name = "company_id", nullable = false)
    protected Long companyId;

    public void setCompanyId(Long companyId) { this.companyId = companyId; }
}
