package exps.cariv.domain.ocr.repository;

import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.document.entity.DocumentType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface OcrParseJobRepository extends JpaRepository<OcrParseJob, Long> {

    // ──────────────────────────────────────────────────────────────
    // 시스템 레벨 쿼리 (백그라운드 워커/스케줄러 전용)
    // 의도적으로 companyId 필터 없음 — 전체 테넌트 대상 복구/관리 목적
    // 사용처: OcrJobWorker.recoverStaleJobs() (@Scheduled)
    // ──────────────────────────────────────────────────────────────

    /** 시스템 복구용: QUEUED 상태 잔여 건 일괄 조회 (전체 테넌트 대상) */
    @Query("SELECT j FROM OcrParseJob j WHERE j.status = :status ORDER BY j.createdAt ASC")
    java.util.List<OcrParseJob> findAllSystemWideByStatus(@Param("status") OcrJobStatus status, Pageable pageable);

    /** 시스템 복구용: PROCESSING 고착 건 조회 (전체 테넌트 대상) */
    @Query("SELECT j FROM OcrParseJob j WHERE j.status = :status AND j.startedAt < :threshold")
    java.util.List<OcrParseJob> findAllSystemWideStuckJobs(@Param("status") OcrJobStatus status,
                                                            @Param("threshold") Instant threshold);

    /** 원자적 claim: 워커가 작업을 가져갈 때 상태 전이 (id 기준, 테넌트 무관) */
    @Modifying
    @Transactional
    @Query("update OcrParseJob j set j.status = :to, j.startedAt = :now " +
            "where j.id = :id and j.status = :from")
    int claim(@Param("id") Long id,
              @Param("from") OcrJobStatus from,
              @Param("to") OcrJobStatus to,
              @Param("now") Instant now);

    /** 재큐잉: 일시적 오류 시 PROCESSING → QUEUED 복원 (id 기준, 테넌트 무관) */
    @Modifying
    @Transactional
    @Query("update OcrParseJob j set j.status = :to, j.startedAt = null, j.finishedAt = null, j.errorMessage = null " +
            "where j.id = :id and j.status = :from")
    int requeue(@Param("id") Long id,
                @Param("from") OcrJobStatus from,
                @Param("to") OcrJobStatus to);

    // ──────────────────────────────────────────────────────────────
    // 테넌트 격리 쿼리 (서비스 레이어 / API용)
    // 반드시 companyId 필터 포함
    // ──────────────────────────────────────────────────────────────

    Optional<OcrParseJob> findByCompanyIdAndId(Long companyId, Long id);

    java.util.List<OcrParseJob> findAllByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);

    java.util.List<OcrParseJob> findByCompanyIdAndVehicleDocumentIdAndDocumentType(
            Long companyId,
            Long vehicleDocumentId,
            DocumentType documentType
    );

    Optional<OcrParseJob> findTopByCompanyIdAndVehicleDocumentIdOrderByCreatedAtDesc(Long companyId, Long vehicleDocumentId);

    Optional<OcrParseJob> findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
            Long companyId,
            Long vehicleDocumentId,
            OcrJobStatus status
    );
}
