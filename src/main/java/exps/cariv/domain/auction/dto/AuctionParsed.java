package exps.cariv.domain.auction.dto;

import java.time.LocalDate;

/**
 * 경락사실확인서 OCR 파싱 결과 DTO.
 */
public record AuctionParsed(
        String registrationNo,           // 차량번호
        String chassisNo,                // 차대번호
        String model,                    // 차명
        Integer modelYear,               // 연식
        Long mileage,                    // 주행거리
        Integer displacement,            // 배기량
        LocalDate initialRegistrationDate, // 최초등록일
        String fuel,                     // 연료
        String color                     // 색상
) {}
