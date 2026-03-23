package exps.cariv.domain.customs.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomsBrokerCreateRequest(
        @NotBlank(message = "관세사명은 필수입니다.")
        @Size(max = 100, message = "관세사명은 100자 이하여야 합니다.")
        String name,

        @Size(max = 30, message = "전화번호는 30자 이하여야 합니다.")
        String phone,

        @Size(max = 120, message = "이메일은 120자 이하여야 합니다.")
        String email
) {
}
