package exps.customs.domain.broker.service;

import exps.customs.domain.broker.dto.ConnectionRequestResponse;
import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.broker.entity.ConnectionStatus;
import exps.customs.domain.broker.repository.BrokerConnectionRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BrokerConnectionService {

    private final BrokerConnectionRepository connectionRepo;

    /** 들어온 연동 요청 목록 */
    @Transactional(readOnly = true)
    public List<ConnectionRequestResponse> listRequests(Long brokerCompanyId, String statusFilter) {
        if (statusFilter != null && !statusFilter.isBlank()) {
            ConnectionStatus st = ConnectionStatus.valueOf(statusFilter.toUpperCase());
            return connectionRepo.findAllByBrokerCompanyIdAndStatusOrderByCreatedAtDesc(brokerCompanyId, st)
                    .stream().map(ConnectionRequestResponse::from).toList();
        }
        return connectionRepo.findAllByBrokerCompanyIdOrderByCreatedAtDesc(brokerCompanyId)
                .stream().map(ConnectionRequestResponse::from).toList();
    }

    /** 대기 중 요청 수 */
    @Transactional(readOnly = true)
    public long pendingCount(Long brokerCompanyId) {
        return connectionRepo.countByBrokerCompanyIdAndStatus(brokerCompanyId, ConnectionStatus.PENDING);
    }

    /** 연동 승인 */
    @Transactional
    public ConnectionRequestResponse approve(Long brokerCompanyId, Long connectionId) {
        BrokerConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "연동 요청을 찾을 수 없습니다."));

        if (!conn.getBrokerCompanyId().equals(brokerCompanyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        if (conn.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 처리된 요청입니다.");
        }

        conn.approve();
        return ConnectionRequestResponse.from(conn);
    }

    /** 연동 거절 */
    @Transactional
    public ConnectionRequestResponse reject(Long brokerCompanyId, Long connectionId) {
        BrokerConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "연동 요청을 찾을 수 없습니다."));

        if (!conn.getBrokerCompanyId().equals(brokerCompanyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        if (conn.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 처리된 요청입니다.");
        }

        conn.reject();
        return ConnectionRequestResponse.from(conn);
    }
}
