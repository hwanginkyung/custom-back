package exps.cariv.domain.customs.repository;

import exps.cariv.domain.customs.entity.CustomsRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomsRequestRepository extends JpaRepository<CustomsRequest, Long> {

    Optional<CustomsRequest> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * 특정 회사의 전송 요청 중, 주어진 차량 ID 목록에 해당하는 요청을 조회.
     * (CustomsRequestVehicle 을 통해 조인 — QueryDSL/JPQL 필요 시 확장)
     */
    List<CustomsRequest> findAllByCompanyId(Long companyId);
}
