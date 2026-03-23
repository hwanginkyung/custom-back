package exps.cariv.domain.shipper.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 화주 추가 요청 DTO.
 * Figma: 화주 이름, 휴대번호, 화주유형.
 */
public record ShipperCreateRequest(
        @NotBlank String name,
        @NotBlank String shipperType,
        String phone
) {}
