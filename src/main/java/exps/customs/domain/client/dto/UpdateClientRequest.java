package exps.customs.domain.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "화주 수정 요청")
public class UpdateClientRequest {

    @Schema(description = "회사명")
    private String companyName;

    @Schema(description = "대표자명")
    private String representativeName;

    @Schema(description = "사업자등록번호")
    private String businessNumber;

    @Schema(description = "전화번호")
    private String phoneNumber;

    @Schema(description = "이메일")
    private String email;

    @Schema(description = "주소")
    private String address;

    @Schema(description = "메모")
    private String memo;
}
