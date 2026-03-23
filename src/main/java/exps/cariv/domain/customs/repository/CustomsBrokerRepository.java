package exps.cariv.domain.customs.repository;

import exps.cariv.domain.customs.entity.CustomsBroker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomsBrokerRepository extends JpaRepository<CustomsBroker, Long> {

    List<CustomsBroker> findAllByCompanyIdAndActiveTrueOrderByNameAsc(Long companyId);

    Optional<CustomsBroker> findByIdAndCompanyIdAndActiveTrue(Long id, Long companyId);

    boolean existsByCompanyIdAndNameIgnoreCaseAndActiveTrue(Long companyId, String name);

    boolean existsByCompanyIdAndNameIgnoreCaseAndActiveTrueAndIdNot(Long companyId, String name, Long id);
}
