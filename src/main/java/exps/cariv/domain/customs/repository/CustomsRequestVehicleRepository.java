package exps.cariv.domain.customs.repository;

import exps.cariv.domain.customs.entity.CustomsRequestVehicle;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomsRequestVehicleRepository extends JpaRepository<CustomsRequestVehicle, Long> {

    List<CustomsRequestVehicle> findAllByCustomsRequestId(Long customsRequestId);

    Optional<CustomsRequestVehicle> findByCustomsRequestIdAndVehicleId(Long customsRequestId, Long vehicleId);

    /**
     * 전송 요청 ID로 차량 아이템 일괄 삭제 (벌크 DELETE).
     */
    @Modifying
    @Query("DELETE FROM CustomsRequestVehicle crv WHERE crv.customsRequest.id = :requestId")
    void deleteAllByCustomsRequestId(@Param("requestId") Long requestId);

    /**
     * 차량 ID 로 연결된 전송 요청 아이템 조회.
     */
    Optional<CustomsRequestVehicle> findFirstByVehicleIdOrderByCreatedAtDesc(Long vehicleId);

    /**
     * 차량 ID 목록으로 관련 아이템 일괄 조회 (CustomsRequest FETCH JOIN).
     * <p>N+1 방지: CustomsRequest 를 함께 로딩한다.</p>
     */
    @EntityGraph(attributePaths = "customsRequest")
    List<CustomsRequestVehicle> findAllByVehicleIdIn(List<Long> vehicleIds);

    boolean existsByCustomsRequestIdAndVehicleId(Long customsRequestId, Long vehicleId);
}
