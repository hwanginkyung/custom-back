package exps.customs.domain.login.repository;

import exps.customs.domain.login.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
}
