package exps.cariv.domain.document.entity;

public enum DocumentStatus {
    UPLOADED,        // S3 업로드 완료
    OCR_QUEUED,      // OCR Job 큐잉됨
    PROCESSING,      // OCR 처리중
    OCR_DRAFT,       // OCR 결과 반영(수정 가능 상태)
    CONFIRMED,       // 확정(선택)
    FAILED           // 실패
}
