package exps.cariv.domain.customs.entity;

/**
 * 관세사 전송 요청 상태.
 */
public enum CustomsRequestStatus {
    DRAFT,          // 작성 중 (차량 선택, 금액 입력)
    SUBMITTED,      // 레거시 상태(신규 플로우에서는 사용하지 않음)
    PROCESSING,     // 관세사 처리 중
    COMPLETED       // 신고필증 발급 완료
}
