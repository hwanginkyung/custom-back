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
@Schema(description = "NCustoms temporary save request with line/container")
public class CreateNcustomsContainerTempSaveRequest {

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
    @Schema(example = "0006")
    private String suchuljaCode;

    @Schema(example = "Sample Exporter")
    private String suchuljaSangho;

    @Schema(example = "C")
    private String suchuljaGbn;

    @Schema(example = "000G")
    private String whajuCode;

    @Schema(example = "Sample Owner")
    private String whajuSangho;

    @Schema(example = "01099990000")
    private String whajuTong;

    @Schema(example = "0000000000")
    private String whajuSaup;

    @Schema(example = "INIDSYST0001C")
    private String gumaejaCode;

    @Schema(example = "A ID SYSTEMS I PVT LTD")
    private String gumaejaSangho;

    @NotBlank
    @Schema(example = "0006")
    private String trustCode;

    @Schema(example = "Sample Broker")
    private String trustSangho;

    @Schema(example = "Sample Road 77")
    private String trustJuso;

    @Schema(example = "Manager")
    private String trustName;

    @Schema(example = "0101151029")
    private String trustTong;

    @Schema(example = "7938500297")
    private String trustSaup;

    @Schema(example = "04530")
    private String trustPost;

    @Schema(example = "Sample detail address")
    private String trustJuso2;

    @Schema(example = "02012014")
    private String trustRoadCd;

    @Schema(example = "02012014")
    private String trustBuildMngNo;

    @Schema(example = "22031")
    private String postCode;

    @Schema(example = "Main exporter address")
    private String juso;

    @Schema(example = "Detail exporter address")
    private String locationAddr;

    @NotBlank
    @Schema(example = "KYR0622")
    private String ivNo;

    @Schema(example = "001")
    private String lanNo;

    @Schema(example = "01")
    private String hangNo;

    @Schema(example = "001")
    private String containerSeqNo;

    @Schema(example = "HSLU5006457")
    private String containerNo;

    @NotBlank
    @Schema(example = "8703239020")
    private String hsCode;

    @Schema(example = "USED CAR")
    private String itemName;

    @Schema(example = "KIA K7 2020-02Y")
    private String itemNameLine1;

    @Schema(example = "2,497cc Gasoline")
    private String itemNameLine3;

    @Schema(example = "1")
    private BigDecimal qty;

    @Schema(example = "1660")
    private BigDecimal totalWeight;

    @Schema(example = "KG")
    private String weightUnit;

    @Schema(example = "1")
    private BigDecimal packageCount;

    @Schema(example = "OU")
    private String packageUnit;

    @Schema(example = "11")
    private String tradeType;

    @Schema(example = "TT")
    private String paymentMethod;

    @Schema(example = "USD")
    private String gyeljeMoney;

    @Schema(example = "1439.26")
    private BigDecimal usdExch;

    @Schema(example = "14200")
    private BigDecimal gyeljeInput;

    @Schema(example = "FOB")
    private String indojo;

    @Schema(example = "KR")
    private String originCountry;

    @Schema(example = "KG")
    private String mokjukCode;

    @Schema(example = "KYRGY")
    private String mokjukName;

    @Schema(example = "KRINC")
    private String hangguCode;

    @Schema(example = "INCHEON")
    private String hangguName;

    @Schema(example = "10")
    private String unsongType;

    @Schema(example = "LC")
    private String unsongBox;

    @Schema(example = "CY")
    private String bondedAreaCode;

    @Schema(example = "HSLU5006457")
    private String banipNo;

    @Schema(example = "Incheon Ro-Ro yard")
    private String warehouseLocation;

    @Schema(example = "N")
    private String refundApplicant;

    @Schema(example = "N")
    private String simpleRefundApplicationYn;

    @Schema(example = "N")
    private String temporaryOpeningNoticeYn;

    @Schema(example = "0001")
    private String agencyCode;

    @Schema(example = "Sample Agency")
    private String agencyName;

    @Schema(example = "AUTO")
    private String procGbn;

    @Schema(example = "B")
    private String singoGbn;

    @Schema(example = "A")
    private String eventType;

    @Schema(example = "Y")
    private String southNorthTradeYn;

    @Schema(example = "admin01")
    private String writerId;

    @Schema(example = "Hong Gil Dong")
    private String writerName;
}
