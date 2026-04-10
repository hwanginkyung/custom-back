package exps.customs.domain.client.repository;

import exps.customs.domain.client.entity.BrokerClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerClientRepository extends JpaRepository<BrokerClient, Long> {
    List<BrokerClient> findAllByCompanyIdOrderByCompanyNameAsc(Long companyId);
    List<BrokerClient> findAllByCompanyIdAndActiveTrue(Long companyId);
    Optional<BrokerClient> findByCompanyIdAndExternalCode(Long companyId, String externalCode);
}
