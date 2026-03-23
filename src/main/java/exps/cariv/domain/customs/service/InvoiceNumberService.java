package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.entity.InvoiceNumberCounter;
import exps.cariv.domain.customs.entity.InvoiceNumberType;
import exps.cariv.domain.customs.repository.InvoiceNumberCounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceNumberService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final InvoiceNumberCounterRepository counterRepository;

    @Transactional
    public String issueNext(Long companyId, Long customsBrokerId, InvoiceNumberType invoiceType) {
        LocalDate businessDate = LocalDate.now(KST);
        Long normalizedCompanyId = normalizeCompanyId(companyId);
        Long normalizedBrokerId = normalizeBrokerId(customsBrokerId);

        InvoiceNumberCounter counter = lockOrCreateCounter(
                normalizedCompanyId,
                normalizedBrokerId,
                invoiceType,
                businessDate
        );
        long sequence = counter.nextSequence();
        counterRepository.save(counter);

        String invoiceNo = formatInvoiceNo(
                businessDate,
                normalizedCompanyId,
                normalizedBrokerId,
                invoiceType,
                sequence
        );
        log.info("[InvoiceNumber] issued {}", invoiceNo);
        return invoiceNo;
    }

    private InvoiceNumberCounter lockOrCreateCounter(Long companyId,
                                                     Long brokerId,
                                                     InvoiceNumberType invoiceType,
                                                     LocalDate businessDate) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var locked = counterRepository.findForUpdate(companyId, brokerId, invoiceType, businessDate);
            if (locked.isPresent()) {
                return locked.get();
            }
            try {
                counterRepository.saveAndFlush(
                        InvoiceNumberCounter.builder()
                                .companyId(companyId)
                                .customsBrokerId(brokerId)
                                .invoiceType(invoiceType)
                                .businessDate(businessDate)
                                .lastSequence(0L)
                                .build()
                );
            } catch (DataIntegrityViolationException e) {
                if (attempt == 2) {
                    throw e;
                }
                log.debug("[InvoiceNumber] counter race detected. retrying...");
            }
        }

        return counterRepository.findForUpdate(companyId, brokerId, invoiceType, businessDate)
                .orElseThrow(() -> new IllegalStateException("Failed to initialize invoice counter"));
    }

    private String formatInvoiceNo(LocalDate businessDate,
                                   Long companyId,
                                   Long brokerId,
                                   InvoiceNumberType invoiceType,
                                   long sequence) {
        return "CI-" + businessDate.format(DATE_FORMATTER)
                + "-C" + padId(companyId)
                + "-B" + padId(brokerId)
                + "-" + invoiceType.code()
                + "-" + String.format("%05d", sequence);
    }

    private Long normalizeCompanyId(Long companyId) {
        return companyId == null || companyId <= 0 ? 0L : companyId;
    }

    private Long normalizeBrokerId(Long brokerId) {
        return brokerId == null || brokerId <= 0 ? 0L : brokerId;
    }

    private String padId(Long value) {
        return String.format("%02d", value);
    }
}
