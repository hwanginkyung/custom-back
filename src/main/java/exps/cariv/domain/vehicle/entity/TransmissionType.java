package exps.cariv.domain.vehicle.entity;

public enum TransmissionType {
    MT,
    AT,
    CVT,
    DCT,
    ETC;

    public static TransmissionType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "MT", "수동" -> MT;
            case "AT", "오토", "자동" -> AT;
            case "CVT" -> CVT;
            case "DCT" -> DCT;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("지원하지 않는 변속기 값입니다: " + raw);
        };
    }
}
