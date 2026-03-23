package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "NCustoms shipper lookup response")
public class NcustomsShipperResponse {

    private final String dealCode;
    private final String dealSaupgbn;
    private final String dealSaup;
    private final String dealSangho;
    private final String dealName;
    private final String dealPost;
    private final String dealJuso;
    private final String dealJuso2;
    private final String dealTel;
    private final String dealFax;
    private final String dealTong;
    private final String roadNmCd;
    private final String buldMngNo;
    private final String addDtTime;
    private final String editDtTime;
}

