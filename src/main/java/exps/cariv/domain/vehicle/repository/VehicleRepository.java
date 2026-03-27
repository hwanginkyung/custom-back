package exps.cariv.domain.vehicle.repository;

import exps.cariv.domain.vehicle.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long>, JpaSpecificationExecutor<Vehicle> {

    Optional<Vehicle> findByIdAndCompanyId(Long id, Long companyId);

    Optional<Vehicle> findByCompanyIdAndVin(Long companyId, String vin);
    Optional<Vehicle> findTopByCompanyIdAndVinAndDeletedFalseOrderByIdDesc(Long companyId, String vin);

    Optional<Vehicle> findByCompanyIdAndVehicleNo(Long companyId, String vehicleNo);

    Optional<Vehicle> findByCompanyIdAndVinAndDeletedFalse(Long companyId, String vin);

    Optional<Vehicle> findByCompanyIdAndVehicleNoAndDeletedFalse(Long companyId, String vehicleNo);

    List<Vehicle> findAllByCompanyIdAndVinAndDeletedFalseOrderByIdDesc(Long companyId, String vin);

    List<Vehicle> findAllByCompanyIdAndVehicleNoAndDeletedFalseOrderByIdDesc(Long companyId, String vehicleNo);

    List<Vehicle> findAllByCompanyIdAndDeletedFalse(Long companyId);

    @Query("SELECT v FROM Vehicle v WHERE v.id = :id AND v.companyId = :companyId AND v.deleted = false")
    Optional<Vehicle> findActiveByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);
}
