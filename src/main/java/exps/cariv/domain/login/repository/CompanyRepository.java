package exps.cariv.domain.login.repository;



import exps.cariv.domain.login.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
}
