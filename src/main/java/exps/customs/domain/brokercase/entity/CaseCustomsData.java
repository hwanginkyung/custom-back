package exps.customs.domain.brokercase.entity;

import exps.customs.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "case_customs_data")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CaseCustomsData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false, unique = true)
    private BrokerCase brokerCase;

    private String declarationNumber;
    private LocalDate declarationDate;
    private LocalDate acceptanceDate;

    @Column(precision = 15, scale = 2)
    private BigDecimal declaredValue;

    @Column(precision = 5, scale = 2)
    private BigDecimal dutyRate;

    @Column(precision = 15, scale = 2)
    private BigDecimal calculatedDuty;

    @Column(precision = 15, scale = 2)
    private BigDecimal calculatedVat;

    private String inspectionResult;
    private String remarks;
}
