package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@Schema(description = "NCustoms expo1 create request")
public class CreateNcustomsExportRequest {

    @NotBlank
    @Pattern(regexp = "\\d{4}", message = "year must be 4 digits")
    @Schema(example = "2026")
    private String year;

    @NotBlank
    @Size(min = 1, max = 1, message = "userCode length must be 1")
    @Schema(example = "4")
    private String userCode;

    @Pattern(regexp = "\\d{8}", message = "singoDate must be yyyyMMdd")
    @Schema(example = "20260303")
    private String singoDate;

    @NotBlank
    @Schema(example = "020")
    private String segwan;

    @NotBlank
    @Schema(example = "09")
    private String gwa;

    @NotBlank
    @Schema(example = "0005")
    private String suchuljaCode;

    @Schema(example = "Sample Exporter Co")
    private String suchuljaSangho;

    @NotBlank
    @Schema(example = "C")
    private String suchuljaGbn;

    @NotBlank
    @Schema(example = "0005")
    private String trustCode;

    @Schema(example = "Sample Broker Co")
    private String trustSangho;

    @Schema(example = "01012345678")
    private String trustTong;

    @Schema(example = "1234567890")
    private String trustSaup;

    @Schema(example = "KGAMANKU0002T")
    private String gumaejaCode;

    @Schema(example = "ARTHUR")
    private String gumaejaSangho;

    @Schema(example = "USD")
    private String gyeljeMoney;

    @Schema(example = "1439.26")
    private BigDecimal usdExch;

    @Schema(example = "14200")
    private BigDecimal gyeljeInput;

    @Schema(example = "22031")
    private String postCode;

    @Schema(example = "Seoul address line 1")
    private String juso;

    @Schema(example = "Seoul address line 2")
    private String locationAddr;

    @Schema(example = "06546")
    private String trustPost;

    @Schema(example = "admin01")
    private String writerId;

    @Schema(example = "Hong Gil Dong")
    private String writerName;

    @Schema(example = "AUTO")
    private String procGbn;

    @Schema(example = "B")
    private String singoGbn;
}
