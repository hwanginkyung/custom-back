package exps.customs.domain.client.dto;

import exps.customs.domain.ncustoms.dto.NcustomsShipperResponse;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ClientSyncPushItem {

    private String dealCode;
    private String dealSaupgbn;
    private String dealSaup;
    private String dealSangho;
    private String dealName;
    private String dealPost;
    private String dealJuso;
    private String dealJuso2;
    private String dealTel;
    private String dealFax;
    private String dealTong;
    private String roadNmCd;
    private String buldMngNo;
    private String addDtTime;
    private String editDtTime;

    public static ClientSyncPushItem fromNcustoms(NcustomsShipperResponse row) {
        ClientSyncPushItem item = new ClientSyncPushItem();
        item.dealCode = row.getDealCode();
        item.dealSaupgbn = row.getDealSaupgbn();
        item.dealSaup = row.getDealSaup();
        item.dealSangho = row.getDealSangho();
        item.dealName = row.getDealName();
        item.dealPost = row.getDealPost();
        item.dealJuso = row.getDealJuso();
        item.dealJuso2 = row.getDealJuso2();
        item.dealTel = row.getDealTel();
        item.dealFax = row.getDealFax();
        item.dealTong = row.getDealTong();
        item.roadNmCd = row.getRoadNmCd();
        item.buldMngNo = row.getBuldMngNo();
        item.addDtTime = row.getAddDtTime();
        item.editDtTime = row.getEditDtTime();
        return item;
    }
}
