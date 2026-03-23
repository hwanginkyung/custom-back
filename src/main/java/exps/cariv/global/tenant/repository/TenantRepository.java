package exps.cariv.global.tenant.repository;

import exps.cariv.global.tenant.TenantContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

@NoRepositoryBean
public interface TenantRepository<T, ID> extends JpaRepository<T, ID> {

    default List<T> findAllByTenant() {
        Long companyId = TenantContext.getCompanyId();
        return findAllByCompanyId(companyId);
    }

    List<T> findAllByCompanyId(Long companyId);
}

