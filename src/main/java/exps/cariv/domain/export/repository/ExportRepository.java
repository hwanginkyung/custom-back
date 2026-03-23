package exps.cariv.domain.export.repository;

import exps.cariv.domain.export.entity.Export;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExportRepository extends JpaRepository<Export, Long> {

    Optional<Export> findByCompanyIdAndVehicleId(Long companyId, Long vehicleId);

    Optional<Export> findFirstByCompanyIdAndVehicleIdOrderByCreatedAtDesc(Long companyId, Long vehicleId);

    Optional<Export> findByCompanyIdAndChassisNo(Long companyId, String chassisNo);
}
