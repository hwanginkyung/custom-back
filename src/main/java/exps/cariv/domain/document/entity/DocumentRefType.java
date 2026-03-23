package exps.cariv.domain.document.entity;

public enum DocumentRefType {
    VEHICLE,   // refId = vehicle.id
    SHIPPER,   // refId = shipper.id (상대 업체)
    AGENT      // refId = baseInfo.id (우리 회사 기본정보)
}
