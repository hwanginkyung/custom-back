package exps.customs.domain.login.dto.response;

import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@Schema(description = "직원 목록 응답 DTO")
public class StaffResponse {

    @Schema(description = "사용자 ID")
    private final Long id;

    @Schema(description = "이메일")
    private final String email;

    @Schema(description = "역할")
    private final Role role;

    @Schema(description = "활성 여부")
    private final boolean active;

    @Schema(description = "생성일")
    private final Instant createdAt;

    public static StaffResponse from(User user) {
        return StaffResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
