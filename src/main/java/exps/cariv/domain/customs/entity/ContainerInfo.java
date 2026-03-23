package exps.cariv.domain.customs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * 컨테이너 정보 (Container 선적 시에만 사용).
 * <p>피그마 기준: 컨넘버, 씰넘버, 반입지, 쇼핑(선명), 수출항, 목적국, Consignee</p>
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ContainerInfo {

    @Column(length = 30)
    private String containerNo;            // 컨넘버

    @Column(length = 30)
    private String sealNo;                 // 씰넘버

    @Column(length = 100)
    private String entryPort;              // 반입지

    @Column(length = 100)
    private String warehouseLocation;      // 쇼링/보관 위치

    @Column(length = 100)
    private String vesselName;             // 선명 (쇼핑)

    @Column(length = 100)
    private String exportPort;             // 수출항

    @Column(length = 100)
    private String destinationCountry;     // 목적국

    @Column(length = 200)
    private String consignee;              // Consignee
}
