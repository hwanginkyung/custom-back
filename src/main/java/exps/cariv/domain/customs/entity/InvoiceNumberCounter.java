package exps.cariv.domain.customs.entity;

import exps.cariv.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Getter
@Table(
        name = "invoice_number_counter",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_invoice_counter_scope",
                columnNames = {"company_id", "customs_broker_id", "invoice_type", "business_date"}
        ),
        indexes = {
                @Index(name = "idx_invoice_counter_scope", columnList = "company_id,customs_broker_id,invoice_type,business_date")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class InvoiceNumberCounter extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "customs_broker_id", nullable = false)
    private Long customsBrokerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 20)
    private InvoiceNumberType invoiceType;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    public long nextSequence() {
        long next = (lastSequence == null ? 0L : lastSequence) + 1L;
        this.lastSequence = next;
        return next;
    }
}
