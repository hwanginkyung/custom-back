package exps.cariv.domain.login.dto.response;

import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "직원 정보 응답 DTO")
public class StaffResponse {

    @Schema(description = "직원 ID", example = "10")
    private final Long id;

    @Schema(description = "직원 이메일", example = "staff@cariv.local")
    private final String email;

    @Schema(description = "활성 여부", example = "true")
    private final boolean active;

    @Schema(description = "권한", example = "STAFF")
    private final Role role;

    public StaffResponse(Long id, String email, boolean active, Role role) {
        this.id = id;
        this.email = email;
        this.active = active;
        this.role = role;
    }

    public static StaffResponse from(User user) {
        return new StaffResponse(
                user.getId(),
                user.getEmail(),
                user.isActive(),
                user.getRole()
        );
    }
}
