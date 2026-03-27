package exps.cariv.domain.broker.service;

import exps.cariv.domain.broker.dto.BrokerCompanyItem;
import exps.cariv.domain.broker.dto.BrokerConnectionResponse;
import exps.cariv.domain.broker.entity.BrokerConnection;
import exps.cariv.domain.broker.entity.ConnectionStatus;
import exps.cariv.domain.broker.repository.BrokerConnectionRepository;
import exps.cariv.domain.login.entity.Company;
import exps.cariv.domain.login.repository.CompanyRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BrokerConnectionService {

    private final BrokerConnectionRepository connectionRepo;
    private final CompanyRepository companyRepo;

    /** 연동 가능한 관세사 회사 목록 (드롭다운) */
    @Transactional(readOnly = true)
    public List<BrokerCompanyItem> listAvailableBrokers() {
        List<BrokerCompanyItem> brokers = connectionRepo.findAllBrokerCompanies().stream()
                .map(row -> new BrokerCompanyItem(
                        ((Number) row[0]).longValue(),
                        (String) row[1]
                ))
                .toList();

        if (brokers.isEmpty()) {
            return List.of(new BrokerCompanyItem(1L, "진솔 관세법인"));
        }
        return brokers;
    }

    /** 내 연동 현황 */
    @Transactional(readOnly = true)
    public List<BrokerConnectionResponse> myConnections(Long companyId) {
        return connectionRepo.findAllByExporterCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(BrokerConnectionResponse::from)
                .toList();
    }

    /** 연동 요청 보내기 */
    @Transactional
    public BrokerConnectionResponse requestConnection(Long exporterCompanyId, Long brokerCompanyId) {
        // 중복 체크
        if (connectionRepo.existsByExporterCompanyIdAndBrokerCompanyId(exporterCompanyId, brokerCompanyId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 연동 요청한 관세사입니다.");
        }

        // 수출자 회사명 조회
        Company exporter = companyRepo.findById(exporterCompanyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "회사를 찾을 수 없습니다."));

        // 관세사 회사명 조회 (native query)
        String brokerName = connectionRepo.findAllBrokerCompanies().stream()
                .filter(row -> ((Number) row[0]).longValue() == brokerCompanyId)
                .map(row -> (String) row[1])
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "관세사 회사를 찾을 수 없습니다."));

        BrokerConnection conn = BrokerConnection.builder()
                .exporterCompanyId(exporterCompanyId)
                .brokerCompanyId(brokerCompanyId)
                .brokerCompanyName(brokerName)
                .exporterCompanyName(exporter.getName())
                .status(ConnectionStatus.PENDING)
                .build();

        conn = connectionRepo.save(conn);
        return BrokerConnectionResponse.from(conn);
    }

    /** 연동 취소 (PENDING 상태에서만) */
    @Transactional
    public void cancelConnection(Long exporterCompanyId, Long connectionId) {
        BrokerConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "연동 요청을 찾을 수 없습니다."));

        if (!conn.getExporterCompanyId().equals(exporterCompanyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        if (conn.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "대기 중인 연동 요청만 취소할 수 있습니다.");
        }

        connectionRepo.delete(conn);
    }

    /** 수출자에게 승인된 관세사가 있는지 확인 */
    @Transactional(readOnly = true)
    public boolean hasApprovedBroker(Long exporterCompanyId) {
        return !connectionRepo.findAllByExporterCompanyIdAndStatus(
                exporterCompanyId, ConnectionStatus.APPROVED
        ).isEmpty();
    }
}
