package exps.customs.domain.login.dto.response;

import exps.customs.domain.login.entity.enumType.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "마이페이지 응답 DTO")
public class MyPageResponse {

    @Schema(description = "사용자 ID")
    private final Long userId;

    @Schema(description = "이메일")
    private final String email;

    @Schema(description = "역할")
    private final Role role;

    @Schema(description = "활성 여부")
    private final boolean active;

    @Schema(description = "회사 ID")
    private final Long companyId;

    @Schema(description = "NCustoms 사용자 코드")
    private final String ncustomsUserCode;

    @Schema(description = "NCustoms 작성자 ID")
    private final String ncustomsWriterId;

    @Schema(description = "NCustoms 작성자명")
    private final String ncustomsWriterName;
}
