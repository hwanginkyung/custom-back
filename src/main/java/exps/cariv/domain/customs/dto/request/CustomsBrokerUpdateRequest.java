package exps.cariv.domain.customs.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CustomsBrokerUpdateRequest(
        @NotBlank(message = "관세사명은 필수입니다.")
        String name,
        String phone,
        String email
) {}
