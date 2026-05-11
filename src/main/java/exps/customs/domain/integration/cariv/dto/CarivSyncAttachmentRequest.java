package exps.customs.domain.integration.cariv.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Cariv 케이스 첨부 동기화 요청(S3 참조 메타데이터)")
public class CarivSyncAttachmentRequest {

    @Schema(description = "첨부 타입(enum 문자열, 없거나 알 수 없으면 OTHER 처리)", example = "INVOICE")
    private String type;

    @Schema(description = "파일명", example = "invoice-2026-0001.pdf")
    private String fileName;

    @NotBlank
    @Schema(description = "파일 경로(S3 key 또는 URL)", example = "vehicles/3/101/owner-id-card/2026/05/xxx.pdf")
    private String filePath;

    @Schema(description = "파일 크기(bytes)", example = "83642")
    private Long fileSize;

    @Schema(description = "MIME 타입", example = "application/pdf")
    private String contentType;
}
