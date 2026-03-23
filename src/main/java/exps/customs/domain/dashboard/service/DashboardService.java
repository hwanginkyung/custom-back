package exps.customs.domain.dashboard.service;

import exps.customs.domain.brokercase.entity.CaseStatus;
import exps.customs.domain.brokercase.entity.PaymentStatus;
import exps.customs.domain.brokercase.repository.BrokerCaseRepository;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.domain.dashboard.dto.DashboardResponse;
import exps.customs.global.tenant.TenantContext;
import exps.customs.global.tenant.aspect.TenantFiltered;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final BrokerCaseRepository caseRepository;
    private final BrokerClientRepository clientRepository;

    @TenantFiltered
    public DashboardResponse getDashboard() {
        Long companyId = TenantContext.getCompanyId();

        return DashboardResponse.builder()
                .totalCases(caseRepository.countByCompanyId(companyId))
                .registeredCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.REGISTERED))
                .inProgressCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.IN_PROGRESS))
                .declaredCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.CUSTOMS_DECLARED))
                .acceptedCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.CUSTOMS_ACCEPTED))
                .arrivalConfirmedCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.ARRIVAL_CONFIRMED))
                .completedCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.COMPLETED))
                .cancelledCases(caseRepository.countByCompanyIdAndStatus(companyId, CaseStatus.CANCELLED))
                .totalClients(clientRepository.findAllByCompanyIdOrderByCompanyNameAsc(companyId).size())
                .unpaidCases(caseRepository.findAllByCompanyIdAndStatus(companyId, CaseStatus.IN_PROGRESS)
                        .stream().filter(c -> c.getPaymentStatus() == PaymentStatus.UNPAID || c.getPaymentStatus() == PaymentStatus.OVERDUE).count())
                .build();
    }
}
