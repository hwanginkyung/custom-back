package exps.customs.domain.client.entity;

import exps.customs.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "broker_client")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BrokerClient extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String companyName;

    private String representativeName;
    private String businessNumber;
    private String phoneNumber;
    private String email;
    private String address;
    private String memo;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
