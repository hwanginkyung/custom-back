package exps.customs.domain.brokercase.entity;

import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "broker_case")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BrokerCase extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String caseNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private BrokerClient client;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CaseStatus status = CaseStatus.REGISTERED;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    private ShippingMethod shippingMethod;

    @Column(length = 50)
    private String blNumber;

    private LocalDate etaDate;
    private LocalDate ataDate;
    private LocalDate customsDate;
    private LocalDate releaseDate;

    private String departurePorts;
    private String arrivalPort;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 15, scale = 2)
    private BigDecimal dutyAmount;

    @Column(precision = 15, scale = 2)
    private BigDecimal vatAmount;

    @Column(precision = 15, scale = 2)
    private BigDecimal brokerageFee;

    @Column(length = 1000)
    private String memo;

    private Long assigneeId;

    @OneToMany(mappedBy = "brokerCase", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CaseCargo> cargos = new ArrayList<>();

    @OneToMany(mappedBy = "brokerCase", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CaseAttachment> attachments = new ArrayList<>();

    @OneToOne(mappedBy = "brokerCase", cascade = CascadeType.ALL, orphanRemoval = true)
    private CaseCustomsData customsData;

    public void addCargo(CaseCargo cargo) {
        cargos.add(cargo);
        cargo.setBrokerCase(this);
    }

    public void addAttachment(CaseAttachment attachment) {
        attachments.add(attachment);
        attachment.setBrokerCase(this);
    }
}
