package exps.customs.domain.broker.repository;

import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.broker.entity.ConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, Long> {

    /** 관세사 기준 – 들어온 연동 요청 목록 */
    List<BrokerConnection> findAllByBrokerCompanyIdOrderByCreatedAtDesc(Long brokerCompanyId);

    /** 관세사 기준 – 상태별 필터 */
    List<BrokerConnection> findAllByBrokerCompanyIdAndStatusOrderByCreatedAtDesc(
            Long brokerCompanyId, ConnectionStatus status);

    /** 대기 중 요청 수 (알림 뱃지용) */
    long countByBrokerCompanyIdAndStatus(Long brokerCompanyId, ConnectionStatus status);

    Optional<BrokerConnection> findByExporterCompanyIdAndBrokerCompanyId(Long exporterCompanyId, Long brokerCompanyId);

    List<BrokerConnection> findAllByExporterCompanyIdOrderByCreatedAtDesc(Long exporterCompanyId);

    List<BrokerConnection> findAllByExporterCompanyIdAndBrokerCompanyIdOrderByCreatedAtDesc(
            Long exporterCompanyId,
            Long brokerCompanyId
    );
}
