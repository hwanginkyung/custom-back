package exps.customs.domain.brokercase.entity;

import exps.customs.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "case_cargo")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CaseCargo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private BrokerCase brokerCase;

    @Column(nullable = false)
    private String itemName;

    private String hsCode;

    @Column(precision = 12, scale = 3)
    private BigDecimal quantity;

    private String unit;

    @Column(precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalPrice;

    @Column(precision = 10, scale = 3)
    private BigDecimal weight;

    private String originCountry;
}
