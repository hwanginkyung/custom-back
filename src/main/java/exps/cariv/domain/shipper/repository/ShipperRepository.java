package exps.cariv.domain.shipper.repository;

import exps.cariv.domain.shipper.entity.Shipper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipperRepository extends JpaRepository<Shipper, Long> {

    Optional<Shipper> findByIdAndCompanyId(Long id, Long companyId);
    Optional<Shipper> findByIdAndCompanyIdAndActiveTrue(Long id, Long companyId);

    List<Shipper> findAllByCompanyIdAndActiveTrue(Long companyId);

    @Query("""
            select s
            from Shipper s
            where s.companyId = :companyId
              and s.active = true
              and (
                    lower(s.name) like lower(concat('%', :query, '%'))
                 or lower(coalesce(s.phone, '')) like lower(concat('%', :query, '%'))
                 or lower(coalesce(s.businessNumber, '')) like lower(concat('%', :query, '%'))
              )
            order by s.createdAt desc, s.id desc
            """)
    List<Shipper> searchActive(@Param("companyId") Long companyId, @Param("query") String query);

    Optional<Shipper> findByCompanyIdAndName(Long companyId, String name);
    Optional<Shipper> findTopByCompanyIdAndNameOrderByIdDesc(Long companyId, String name);
}
