package exps.cariv.domain.malso.entity;

import exps.cariv.domain.vehicle.entity.VehicleStage;

/**
 * 말소 페이지 전용 상태.
 *
 * <p>피그마 기준:
 * <ul>
 *   <li>대기 - 말소증 업로드 전, 출력도 안 한 상태</li>
 *   <li>진행 - 한 번이라도 전체출력을 수행한 상태</li>
 *   <li>완료 - 말소증이 업로드(OCR 포함)된 상태</li>
 * </ul>
 *
 * <p>VehicleStage 매핑:
 * <ul>
 *   <li>BEFORE_DEREGISTRATION → 대기 (기본) 또는 진행 (출력 이력 있을 때)</li>
 *   <li>BEFORE_REPORT → 완료 (말소증 업로드 후 다음 단계로 넘어간 상태)</li>
 *   <li>BEFORE_CERTIFICATE, COMPLETED → 완료</li>
 * </ul>
 */
public enum MalsoStatus {
    WAITING("대기"),
    IN_PROGRESS("진행"),
    DONE("완료");

    private final String label;

    MalsoStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * VehicleStage + 말소증 업로드 여부 + 출력 이력 여부로 말소 상태를 결정합니다.
     */
    public static MalsoStatus from(VehicleStage stage, boolean hasDeregistrationDoc, boolean hasPrintHistory) {
        // 말소증이 업로드되었으면 → 완료
        if (hasDeregistrationDoc) return DONE;

        // 아직 말소증 미업로드 → 출력 이력에 따라 대기/진행
        if (hasPrintHistory) return IN_PROGRESS;

        return WAITING;
    }
}
