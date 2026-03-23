package exps.cariv.domain.customs.entity;

import exps.cariv.domain.vehicle.entity.VehicleStage;

/**
 * 신고필증 페이지에서 보여주는 차량 상태.
 * <p>피그마: 대기(노란색), 진행(파란색), 완료(초록색)</p>
 *
 * <ul>
 *   <li>대기 — 아직 관세사 전송 안 함</li>
 *   <li>진행 — 관세사 전송 완료, 아직 신고필증 미발급</li>
 *   <li>완료 — 수출신고필증 업로드(OCR) 완료</li>
 * </ul>
 */
public enum CustomsStatus {

    WAITING("대기"),
    IN_PROGRESS("진행"),
    DONE("완료");

    private final String label;

    CustomsStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 차량 상태와 전송 이력으로 신고필증 상태를 계산.
     *
     * @param stage             현재 VehicleStage
     * @param hasExportDoc      수출신고필증 문서 존재 여부
     * @param hasCustomsRequest 관세사 전송 이력 존재 여부
     */
    public static CustomsStatus from(VehicleStage stage, boolean hasExportDoc, boolean hasCustomsRequest) {
        if (hasExportDoc) return DONE;
        if (hasCustomsRequest) return IN_PROGRESS;
        return WAITING;
    }
}
