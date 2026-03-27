package exps.cariv.domain.broker.entity;

public enum ConnectionStatus {
    PENDING,    // 수출자가 요청함, 관세사 승인 대기
    APPROVED,   // 관세사가 승인
    REJECTED    // 관세사가 거절
}
