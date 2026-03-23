package exps.customs.domain.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "화주 생성 요청")
public class CreateClientRequest {

    @NotBlank(message = "회사명은 필수입니다.")
    @Schema(description = "회사명", example = "(주)한국무역")
    private String companyName;

    @Schema(description = "대표자명", example = "홍길동")
    private String representativeName;

    @Schema(description = "사업자등록번호", example = "123-45-67890")
    private String businessNumber;

    @Schema(description = "전화번호", example = "02-1234-5678")
    private String phoneNumber;

    @Schema(description = "이메일", example = "contact@hankook.co.kr")
    private String email;

    @Schema(description = "주소", example = "서울시 강남구 역삼동 123-45")
    private String address;

    @Schema(description = "메모")
    private String memo;
}
