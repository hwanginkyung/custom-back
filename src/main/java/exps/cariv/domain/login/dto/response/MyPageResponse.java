package exps.cariv.domain.login.dto.response;

import exps.cariv.domain.login.entity.Company;
import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.List;

@Getter
@Schema(description = "마이페이지 응답 DTO")
public class MyPageResponse {

    @Schema(description = "사용자 ID", example = "1")
    private final Long userId;

    @Schema(description = "로그인 ID", example = "admin@cariv.local")
    private final String loginId;

    @Schema(description = "이메일", example = "admin@cariv.local")
    private final String email;

    @Schema(description = "권한", example = "ADMIN")
    private final Role role;

    @Schema(description = "회사 ID", example = "1")
    private final Long companyId;

    @Schema(description = "회사명", example = "CARIV")
    private final String companyName;

    @Schema(description = "직원 목록")
    private final List<StaffResponse> staffList;

    public MyPageResponse(Long userId,
                          String loginId,
                          String email,
                          Role role,
                          Long companyId,
                          String companyName,
                          List<StaffResponse> staffList) {
        this.userId = userId;
        this.loginId = loginId;
        this.email = email;
        this.role = role;
        this.companyId = companyId;
        this.companyName = companyName;
        this.staffList = staffList;
    }

    public static MyPageResponse from(User user, Company company, List<StaffResponse> staffList) {
        return new MyPageResponse(
                user.getId(),
                user.getLoginId(),
                user.getEmail(),
                user.getRole(),
                company.getId(),
                company.getName(),
                staffList
        );
    }
}
