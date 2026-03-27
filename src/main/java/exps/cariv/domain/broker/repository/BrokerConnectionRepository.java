package exps.cariv.domain.broker.repository;

import exps.cariv.domain.broker.entity.BrokerConnection;
import exps.cariv.domain.broker.entity.ConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, Long> {

    /** 수출자 기준 – 내 연동 목록 */
    List<BrokerConnection> findAllByExporterCompanyIdOrderByCreatedAtDesc(Long exporterCompanyId);

    /** 수출자 기준 – 승인된 연동만 */
    List<BrokerConnection> findAllByExporterCompanyIdAndStatus(Long exporterCompanyId, ConnectionStatus status);

    /** 중복 방지 */
    boolean existsByExporterCompanyIdAndBrokerCompanyId(Long exporterCompanyId, Long brokerCompanyId);

    Optional<BrokerConnection> findByExporterCompanyIdAndBrokerCompanyId(Long exporterCompanyId, Long brokerCompanyId);

    /**
     * 연동 가능한 관세사 회사 목록 조회.
     * cariv-customs 쪽 Company 테이블에서 company_name이 있는 행 = 관세사 회사.
     */
    @Query(value = "SELECT c.id, c.company_name FROM company c WHERE c.company_name IS NOT NULL ORDER BY c.company_name",
            nativeQuery = true)
    List<Object[]> findAllBrokerCompanies();
}
