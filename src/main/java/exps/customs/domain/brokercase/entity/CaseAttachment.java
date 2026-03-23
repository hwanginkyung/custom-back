package exps.customs.domain.brokercase.entity;

import exps.customs.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "case_attachment")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CaseAttachment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private BrokerCase brokerCase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttachmentType type;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String filePath;

    private Long fileSize;

    private String contentType;
}
