package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.dto.request.CustomsBrokerCreateRequest;
import exps.cariv.domain.customs.dto.request.CustomsBrokerUpdateRequest;
import exps.cariv.domain.customs.dto.response.CustomsBrokerResponse;
import exps.cariv.domain.customs.entity.CustomsBroker;
import exps.cariv.domain.customs.repository.CustomsBrokerRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomsBrokerService {

    private static final String ONLY_BROKER_NAME = "진솔관세법인";

    private final CustomsBrokerRepository brokerRepo;

    @Transactional(readOnly = true)
    public List<CustomsBrokerResponse> list(Long companyId) {
        return brokerRepo.findAllByCompanyIdAndActiveTrueOrderByNameAsc(companyId).stream()
                .filter(b -> ONLY_BROKER_NAME.equals(b.getName()))
                .map(b -> new CustomsBrokerResponse(
                        b.getId(),
                        b.getName(),
                        b.getPhone(),
                        b.getEmail()
                ))
                .toList();
    }

    @Transactional
    public Long create(Long companyId, CustomsBrokerCreateRequest req) {
        String name = req.name().trim();
        validateOnlyJinsol(name);

        CustomsBroker existing = brokerRepo.findByCompanyIdAndNameIgnoreCaseAndActiveTrue(companyId, ONLY_BROKER_NAME)
                .orElse(null);
        if (existing != null) {
            existing.update(ONLY_BROKER_NAME, trimToNull(req.phone()), trimToNull(req.email()));
            existing.activate();
            brokerRepo.save(existing);
            return existing.getId();
        }

        if (brokerRepo.existsByCompanyIdAndNameIgnoreCaseAndActiveTrue(companyId, name)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "동일한 관세사명이 이미 존재합니다.");
        }

        CustomsBroker broker = CustomsBroker.builder()
                .name(ONLY_BROKER_NAME)
                .phone(trimToNull(req.phone()))
                .email(trimToNull(req.email()))
                .active(true)
                .build();
        broker.setCompanyId(companyId);
        broker = brokerRepo.save(broker);
        return broker.getId();
    }

    @Transactional
    public void update(Long companyId, Long brokerId, CustomsBrokerUpdateRequest req) {
        CustomsBroker broker = brokerRepo.findByIdAndCompanyIdAndActiveTrue(brokerId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "관세사를 찾을 수 없습니다."));

        String name = req.name().trim();
        validateOnlyJinsol(name);

        if (brokerRepo.existsByCompanyIdAndNameIgnoreCaseAndActiveTrueAndIdNot(companyId, name, brokerId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "동일한 관세사명이 이미 존재합니다.");
        }

        broker.update(ONLY_BROKER_NAME, trimToNull(req.phone()), trimToNull(req.email()));
    }

    @Transactional
    public void delete(Long companyId, Long brokerId) {
        CustomsBroker broker = brokerRepo.findByIdAndCompanyIdAndActiveTrue(brokerId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "관세사를 찾을 수 없습니다."));
        if (ONLY_BROKER_NAME.equals(broker.getName())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "진솔관세법인은 삭제할 수 없습니다.");
        }
        broker.deactivate();
    }

    private void validateOnlyJinsol(String name) {
        if (!ONLY_BROKER_NAME.equals(name)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "현재는 진솔관세법인만 등록할 수 있습니다.");
        }
    }

    private String trimToNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return v.isBlank() ? null : v;
    }
}
