package exps.customs.domain.broker.service;

import exps.customs.domain.broker.dto.ConnectionRequestResponse;
import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.broker.entity.ConnectionStatus;
import exps.customs.domain.broker.repository.BrokerConnectionRepository;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BrokerConnectionService {

    private final BrokerConnectionRepository connectionRepo;
    private final BrokerClientRepository clientRepo;

    /** 들어온 연동 요청 목록 */
    @Transactional(readOnly = true)
    public List<ConnectionRequestResponse> listRequests(Long brokerCompanyId, String statusFilter) {
        List<BrokerConnection> requests;
        if (statusFilter != null && !statusFilter.isBlank()) {
            ConnectionStatus st = ConnectionStatus.valueOf(statusFilter.toUpperCase());
            requests = connectionRepo.findAllByBrokerCompanyIdAndStatusOrderByCreatedAtDesc(brokerCompanyId, st);
        } else {
            requests = connectionRepo.findAllByBrokerCompanyIdOrderByCreatedAtDesc(brokerCompanyId);
        }
        return buildResponses(brokerCompanyId, requests);
    }

    /** 대기 중 요청 수 */
    @Transactional(readOnly = true)
    public long pendingCount(Long brokerCompanyId) {
        return connectionRepo.countByBrokerCompanyIdAndStatus(brokerCompanyId, ConnectionStatus.PENDING);
    }

    /** 연동 승인 */
    @Transactional
    public ConnectionRequestResponse approve(Long brokerCompanyId, Long connectionId, Long matchedClientId) {
        BrokerConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "연동 요청을 찾을 수 없습니다."));

        if (!conn.getBrokerCompanyId().equals(brokerCompanyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        if (conn.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 처리된 요청입니다.");
        }

        if (matchedClientId != null) {
            BrokerClient selectedClient = clientRepo.findByIdAndCompanyId(matchedClientId, brokerCompanyId)
                    .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND, "선택한 화주를 찾을 수 없습니다."));
            applyMatchedClientLink(conn, selectedClient);
            conn.approve(selectedClient.getId());
        } else {
            List<BrokerClient> activeClients = clientRepo.findAllByCompanyIdAndActiveTrue(brokerCompanyId);
            MatchCandidate candidate = findMatchedClient(conn, activeClients);
            if (candidate.client() != null) {
                applyMatchedClientLink(conn, candidate.client());
                conn.approve(candidate.client().getId());
            } else {
                conn.approve();
            }
        }

        return buildResponses(brokerCompanyId, List.of(conn)).get(0);
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
        return buildResponses(brokerCompanyId, List.of(conn)).get(0);
    }

    /** 승인/연결 상태의 요청에 화주 링크를 수동 지정 */
    @Transactional
    public ConnectionRequestResponse linkClient(Long brokerCompanyId, Long connectionId, Long matchedClientId) {
        if (matchedClientId == null || matchedClientId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "matchedClientId가 필요합니다.");
        }

        BrokerConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "연동 요청을 찾을 수 없습니다."));

        if (!conn.getBrokerCompanyId().equals(brokerCompanyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        if (conn.getStatus() == ConnectionStatus.REJECTED) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "거절된 요청은 화주를 연결할 수 없습니다.");
        }

        BrokerClient selectedClient = clientRepo.findByIdAndCompanyId(matchedClientId, brokerCompanyId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND, "선택한 화주를 찾을 수 없습니다."));

        applyMatchedClientLink(conn, selectedClient);
        conn.linkClient(selectedClient.getId());
        return buildResponses(brokerCompanyId, List.of(conn)).get(0);
    }

    private List<ConnectionRequestResponse> buildResponses(Long brokerCompanyId, List<BrokerConnection> requests) {
        List<BrokerClient> allClients = clientRepo.findAllByCompanyIdOrderByCompanyNameAsc(brokerCompanyId);
        Map<Long, BrokerClient> clientById = allClients.stream()
                .collect(Collectors.toMap(BrokerClient::getId, Function.identity(), (a, b) -> a));
        List<BrokerClient> activeClients = allClients.stream()
                .filter(BrokerClient::isActive)
                .toList();

        return requests.stream()
                .map(conn -> {
                    MatchCandidate candidate = findMatchedClient(conn, activeClients);
                    BrokerClient linkedClient = conn.getLinkedClientId() == null
                            ? null
                            : clientById.get(conn.getLinkedClientId());
                    return ConnectionRequestResponse.from(
                            conn,
                            candidate.client(),
                            candidate.matchedBy(),
                            linkedClient
                    );
                })
                .toList();
    }

    private MatchCandidate findMatchedClient(BrokerConnection conn, List<BrokerClient> activeClients) {
        if (activeClients == null || activeClients.isEmpty()) {
            return MatchCandidate.none();
        }

        String exporterExternalCode = conn.getExporterCompanyId() == null
                ? null
                : String.valueOf(conn.getExporterCompanyId());
        if (exporterExternalCode != null) {
            BrokerClient byCode = activeClients.stream()
                    .filter(client -> exporterExternalCode.equals(trimToNull(client.getExternalCode())))
                    .findFirst()
                    .orElse(null);
            if (byCode != null) {
                return new MatchCandidate(byCode, "EXTERNAL_CODE");
            }
        }

        String normalizedBizNo = normalizeBusinessNumber(conn.getExporterBusinessNumber());
        if (normalizedBizNo != null) {
            BrokerClient byBizNo = activeClients.stream()
                    .filter(client -> Objects.equals(normalizedBizNo, normalizeBusinessNumber(client.getBusinessNumber())))
                    .findFirst()
                    .orElse(null);
            if (byBizNo != null) {
                return new MatchCandidate(byBizNo, "BUSINESS_NUMBER");
            }
        }

        String normalizedName = normalizeName(conn.getExporterCompanyName());
        if (normalizedName != null) {
            BrokerClient byName = activeClients.stream()
                    .filter(client -> Objects.equals(normalizedName, normalizeName(client.getCompanyName())))
                    .findFirst()
                    .orElse(null);
            if (byName != null) {
                return new MatchCandidate(byName, "COMPANY_NAME");
            }
        }
        return MatchCandidate.none();
    }

    private void applyMatchedClientLink(BrokerConnection conn, BrokerClient client) {
        String exporterExternalCode = conn.getExporterCompanyId() == null
                ? null
                : String.valueOf(conn.getExporterCompanyId());

        if (trimToNull(client.getExternalCode()) == null && exporterExternalCode != null) {
            client.setExternalCode(exporterExternalCode);
        }
        if (trimToNull(client.getBusinessNumber()) == null && trimToNull(conn.getExporterBusinessNumber()) != null) {
            client.setBusinessNumber(conn.getExporterBusinessNumber().trim());
        }
        if (trimToNull(client.getCompanyName()) == null && trimToNull(conn.getExporterCompanyName()) != null) {
            client.setCompanyName(conn.getExporterCompanyName().trim());
        }
        clientRepo.save(client);
    }

    private String normalizeBusinessNumber(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    private String normalizeName(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record MatchCandidate(BrokerClient client, String matchedBy) {
        private static MatchCandidate none() {
            return new MatchCandidate(null, null);
        }
    }
}
