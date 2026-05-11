package exps.customs.domain.brokercase.service;

import exps.customs.domain.brokercase.dto.*;
import exps.customs.domain.brokercase.entity.*;
import exps.customs.domain.brokercase.repository.BrokerCaseRepository;
import exps.customs.domain.brokercase.repository.CaseAttachmentRepository;
import exps.customs.domain.brokercase.repository.CaseCargoRepository;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import exps.customs.global.tenant.TenantContext;
import exps.customs.global.tenant.aspect.TenantFiltered;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CaseService {

    private final BrokerCaseRepository caseRepository;
    private final CaseCargoRepository cargoRepository;
    private final CaseAttachmentRepository attachmentRepository;
    private final BrokerClientRepository clientRepository;

    @TenantFiltered
    public List<CaseResponse> getAll() {
        Long companyId = TenantContext.getCompanyId();
        return caseRepository.findAllByCompanyIdOrderByCreatedAtDesc(companyId)
                .stream().map(CaseResponse::from).toList();
    }

    @TenantFiltered
    public List<CaseResponse> getByStatus(CaseStatus status) {
        Long companyId = TenantContext.getCompanyId();
        return caseRepository.findAllByCompanyIdAndStatus(companyId, status)
                .stream().map(CaseResponse::from).toList();
    }

    @TenantFiltered
    public List<CaseResponse> getByClient(Long clientId) {
        Long companyId = TenantContext.getCompanyId();
        return caseRepository.findAllByCompanyIdAndClientId(companyId, clientId)
                .stream().map(CaseResponse::from).toList();
    }

    @TenantFiltered
    public CaseResponse getById(Long id) {
        BrokerCase brokerCase = caseRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.CASE_NOT_FOUND));
        return CaseResponse.from(brokerCase);
    }

    @TenantFiltered
    public List<CaseAttachmentResponse> getAttachments(Long caseId) {
        // Tenant filter is applied through BrokerCase lookup.
        caseRepository.findById(caseId)
                .orElseThrow(() -> new CustomException(ErrorCode.CASE_NOT_FOUND));

        return attachmentRepository.findAllByBrokerCaseId(caseId).stream()
                .sorted(Comparator.comparing(CaseAttachment::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(CaseAttachmentResponse::from)
                .toList();
    }

    @Transactional
    public CaseResponse create(CreateCaseRequest req) {
        if (caseRepository.findByCaseNumber(req.getCaseNumber()).isPresent()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 존재하는 케이스 번호입니다.");
        }

        BrokerClient client = clientRepository.findById(req.getClientId())
                .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND));

        BrokerCase brokerCase = BrokerCase.builder()
                .caseNumber(req.getCaseNumber())
                .client(client)
                .status(CaseStatus.REGISTERED)
                .paymentStatus(PaymentStatus.UNPAID)
                .shippingMethod(req.getShippingMethod())
                .blNumber(req.getBlNumber())
                .etaDate(req.getEtaDate())
                .departurePorts(req.getDeparturePorts())
                .arrivalPort(req.getArrivalPort())
                .totalAmount(req.getTotalAmount())
                .memo(req.getMemo())
                .assigneeId(req.getAssigneeId())
                .build();

        caseRepository.save(brokerCase);
        log.info("[Case] created id={}, caseNumber={}", brokerCase.getId(), brokerCase.getCaseNumber());
        return CaseResponse.from(brokerCase);
    }

    @Transactional
    public CaseResponse update(Long id, UpdateCaseRequest req) {
        BrokerCase c = caseRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.CASE_NOT_FOUND));

        if (req.getStatus() != null) c.setStatus(req.getStatus());
        if (req.getPaymentStatus() != null) c.setPaymentStatus(req.getPaymentStatus());
        if (req.getShippingMethod() != null) c.setShippingMethod(req.getShippingMethod());
        if (req.getBlNumber() != null) c.setBlNumber(req.getBlNumber());
        if (req.getEtaDate() != null) c.setEtaDate(req.getEtaDate());
        if (req.getAtaDate() != null) c.setAtaDate(req.getAtaDate());
        if (req.getCustomsDate() != null) c.setCustomsDate(req.getCustomsDate());
        if (req.getReleaseDate() != null) c.setReleaseDate(req.getReleaseDate());
        if (req.getDeparturePorts() != null) c.setDeparturePorts(req.getDeparturePorts());
        if (req.getArrivalPort() != null) c.setArrivalPort(req.getArrivalPort());
        if (req.getTotalAmount() != null) c.setTotalAmount(req.getTotalAmount());
        if (req.getDutyAmount() != null) c.setDutyAmount(req.getDutyAmount());
        if (req.getVatAmount() != null) c.setVatAmount(req.getVatAmount());
        if (req.getBrokerageFee() != null) c.setBrokerageFee(req.getBrokerageFee());
        if (req.getMemo() != null) c.setMemo(req.getMemo());
        if (req.getAssigneeId() != null) c.setAssigneeId(req.getAssigneeId());

        caseRepository.save(c);
        log.info("[Case] updated id={}", id);
        return CaseResponse.from(c);
    }

    @Transactional
    public CargoResponse addCargo(Long caseId, CreateCargoRequest req) {
        BrokerCase brokerCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new CustomException(ErrorCode.CASE_NOT_FOUND));

        CaseCargo cargo = CaseCargo.builder()
                .itemName(req.getItemName())
                .hsCode(req.getHsCode())
                .quantity(req.getQuantity())
                .unit(req.getUnit())
                .unitPrice(req.getUnitPrice())
                .totalPrice(req.getTotalPrice())
                .weight(req.getWeight())
                .originCountry(req.getOriginCountry())
                .build();
        brokerCase.addCargo(cargo);
        caseRepository.save(brokerCase);

        log.info("[Case] cargo added caseId={}, cargoId={}", caseId, cargo.getId());
        return CargoResponse.from(cargo);
    }

    @Transactional
    public void removeCargo(Long caseId, Long cargoId) {
        BrokerCase brokerCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new CustomException(ErrorCode.CASE_NOT_FOUND));
        brokerCase.getCargos().removeIf(c -> c.getId().equals(cargoId));
        caseRepository.save(brokerCase);
        log.info("[Case] cargo removed caseId={}, cargoId={}", caseId, cargoId);
    }
}
