package exps.cariv.domain.auction.dto.request;

import java.time.LocalDate;

/**
 * 경락사실확인서 OCR 스냅샷 수동 수정 요청.
 * pre-create 업로드 플로우에서 documentId 기준으로 저장한다.
 */
public record AuctionSnapshotUpdateRequest(
        String registrationNo,
        String chassisNo,
        String model,
        Integer modelYear,
        Long mileage,
        Integer displacement,
        LocalDate initialRegistrationDate,
        String fuel,
        String color
) {}
