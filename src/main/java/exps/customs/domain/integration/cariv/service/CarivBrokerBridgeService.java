package exps.customs.domain.integration.cariv.service;

import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.broker.entity.ConnectionStatus;
import exps.customs.domain.broker.repository.BrokerConnectionRepository;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.domain.integration.cariv.dto.CarivBrokerConnectionRequest;
import exps.customs.domain.integration.cariv.dto.CarivBrokerOptionResponse;
import exps.customs.domain.login.entity.Company;
import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import exps.customs.domain.login.repository.CompanyRepository;
import exps.customs.domain.login.repository.UserRepository;
import exps.customs.domain.notification.service.BrokerNotificationService;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CarivBrokerBridgeService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final BrokerConnectionRepository connectionRepository;
    private final BrokerClientRepository clientRepository;
    private final BrokerNotificationService notificationService;

    @Transactional(readOnly = true)
    public List<CarivBrokerOptionResponse> listBrokerOptions(Long exporterCompanyId) {
        if (exporterCompanyId == null || exporterCompanyId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "exporterCompanyId is required");
        }

        Map<Long, Company> brokerCompanyMap = loadBrokerCompanies();
        Map<Long, BrokerConnection> latestConnectionByBroker = connectionRepository
                .findAllByExporterCompanyIdOrderByCreatedAtDesc(exporterCompanyId)
                .stream()
                .filter(conn -> conn.getBrokerCompanyId() != null)
                .collect(Collectors.toMap(
                        BrokerConnection::getBrokerCompanyId,
                        conn -> conn,
                        (existing, ignored) -> existing,
                        LinkedHashMap::new
                ));

        return brokerCompanyMap.values().stream()
                .sorted(Comparator.comparing(c -> nullSafe(c.getCompanyName())))
                .map(company -> {
                    BrokerConnection conn = latestConnectionByBroker.get(company.getId());
                    if (conn == null) {
                        return CarivBrokerOptionResponse.ofNotRequested(
                                company.getId(),
                                company.getCompanyName(),
                                company.getBusinessNumber());
                    }
                    return CarivBrokerOptionResponse.ofConnection(
                            company.getId(),
                            company.getCompanyName(),
                            company.getBusinessNumber(),
                            conn.getId(),
                            conn.getStatus(),
                            conn.getApprovedAt(),
                            conn.getLinkedClientId(),
                            resolveLinkedClientName(conn));
                })
                .toList();
    }

    @Transactional
    public CarivBrokerOptionResponse requestConnection(CarivBrokerConnectionRequest request) {
        if (request == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "request body is required");
        }
        Long exporterCompanyId = request.getExporterCompanyId();
        Long brokerCompanyId = request.getBrokerCompanyId();
        if (exporterCompanyId == null || exporterCompanyId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "exporterCompanyId is required");
        }
        if (brokerCompanyId == null || brokerCompanyId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "brokerCompanyId is required");
        }

        Map<Long, Company> brokerCompanyMap = loadBrokerCompanies();
        Company brokerCompany = brokerCompanyMap.get(brokerCompanyId);
        if (brokerCompany == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "관세사 업체를 찾을 수 없습니다.");
        }

        BrokerConnection connection = connectionRepository
                .findAllByExporterCompanyIdAndBrokerCompanyIdOrderByCreatedAtDesc(exporterCompanyId, brokerCompanyId)
                .stream()
                .findFirst()
                .orElse(null);

        boolean shouldNotify = false;
        if (connection == null) {
            connection = connectionRepository.save(BrokerConnection.builder()
                    .exporterCompanyId(exporterCompanyId)
                    .exporterCompanyName(request.getExporterCompanyName())
                    .exporterBusinessNumber(request.getExporterBusinessNumber())
                    .brokerCompanyId(brokerCompanyId)
                    .brokerCompanyName(brokerCompany.getCompanyName())
                    .status(ConnectionStatus.PENDING)
                    .build());
            shouldNotify = true;
        } else {
            connection.updateExporterProfile(
                    request.getExporterCompanyName(),
                    request.getExporterBusinessNumber()
            );
            if (connection.getStatus() != ConnectionStatus.APPROVED) {
                connection.request();
                shouldNotify = true;
            }
        }

        if (shouldNotify) {
            notificationService.notifyConnectionRequested(connection);
        }

        return CarivBrokerOptionResponse.ofConnection(
                brokerCompany.getId(),
                brokerCompany.getCompanyName(),
                brokerCompany.getBusinessNumber(),
                connection.getId(),
                connection.getStatus(),
                connection.getApprovedAt(),
                connection.getLinkedClientId(),
                resolveLinkedClientName(connection));
    }

    private Map<Long, Company> loadBrokerCompanies() {
        Set<Long> brokerCompanyIds = loadActiveBrokerUsers().stream()
                .map(User::getCompanyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return companyRepository.findAllById(brokerCompanyIds).stream()
                .filter(company -> trimToNull(company.getCompanyName()) != null)
                .filter(company -> trimToNull(company.getBusinessNumber()) != null)
                .collect(Collectors.toMap(Company::getId, company -> company));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String resolveLinkedClientName(BrokerConnection connection) {
        if (connection == null || connection.getLinkedClientId() == null || connection.getBrokerCompanyId() == null) {
            return null;
        }
        BrokerClient client = clientRepository
                .findByIdAndCompanyId(connection.getLinkedClientId(), connection.getBrokerCompanyId())
                .orElse(null);
        return client == null ? null : client.getCompanyName();
    }

    private List<User> loadActiveBrokerUsers() {
        return userRepository.findActiveBrokerUsersWithNcustomsProfile(
                List.of(Role.ADMIN, Role.MASTER)
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public void disconnectConnection(Long clientId) {
        // clientId(=exporterCompanyId)로 APPROVED 상태인 연동을 찾아 DISCONNECTED로 변경
        BrokerConnection connection = connectionRepository
                .findByExporterCompanyIdAndStatus(clientId, ConnectionStatus.APPROVED)
                .orElseThrow(() -> new RuntimeException("연동된 관계를 찾을 수 없습니다: clientId=" + clientId));
        connection.disconnect();
    }
}
