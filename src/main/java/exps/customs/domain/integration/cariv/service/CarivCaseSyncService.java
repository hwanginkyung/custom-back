package exps.customs.domain.integration.cariv.service;

import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.broker.entity.ConnectionStatus;
import exps.customs.domain.broker.repository.BrokerConnectionRepository;
import exps.customs.domain.brokercase.entity.AttachmentType;
import exps.customs.domain.brokercase.entity.BrokerCase;
import exps.customs.domain.brokercase.entity.CaseAttachment;
import exps.customs.domain.brokercase.entity.CaseCargo;
import exps.customs.domain.brokercase.entity.CaseStatus;
import exps.customs.domain.brokercase.entity.PaymentStatus;
import exps.customs.domain.brokercase.entity.ShippingMethod;
import exps.customs.domain.brokercase.repository.BrokerCaseRepository;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.domain.integration.cariv.dto.CarivDummySeedResponse;
import exps.customs.domain.integration.cariv.dto.CarivSyncAttachmentRequest;
import exps.customs.domain.integration.cariv.dto.CarivSyncCargoRequest;
import exps.customs.domain.integration.cariv.dto.CarivSyncCaseRequest;
import exps.customs.domain.integration.cariv.dto.CarivSyncCaseResponse;
import exps.customs.domain.login.repository.CompanyRepository;
import exps.customs.domain.notification.service.BrokerNotificationService;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class CarivCaseSyncService {

    private static final DateTimeFormatter CASE_NUMBER_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BrokerConnectionRepository connectionRepository;
    private final BrokerClientRepository clientRepository;
    private final BrokerCaseRepository caseRepository;
    private final CompanyRepository companyRepository;
    private final BrokerNotificationService notificationService;

    @Value("${cariv.sync.push.agent-token:}")
    private String pushAgentToken;

    @Value("${cariv.sync.push.previous-agent-token:}")
    private String previousPushAgentToken;

    @Value("${cariv.sync.push.require-https:false}")
    private boolean requireHttpsForPush;

    @Value("${cariv.sync.push.allowed-ips:}")
    private String allowedPushIpsRaw;

    @Transactional
    public CarivSyncCaseResponse syncCase(Long brokerCompanyId, CarivSyncCaseRequest req) {
        if (brokerCompanyId == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "회사 정보가 없습니다.");
        }
        if (req == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 본문이 비어 있습니다.");
        }

        Long exporterCompanyId = resolveExporterCompanyId(req.getExporterCompanyId());
        BrokerConnection connection = upsertConnection(
                brokerCompanyId,
                exporterCompanyId,
                req.getExporterCompanyName(),
                req.getExporterBusinessNumber(),
                req.getConnectionStatus()
        );
        BrokerClient client = resolveClientForCase(
                connection,
                brokerCompanyId,
                exporterCompanyId,
                req.getExporterCompanyName(),
                req.getExporterBusinessNumber(),
                req.getExporterPhoneNumber(),
                req.getExporterEmail()
        );

        String resolvedCaseNumber = resolveCaseNumber(req.getExternalCaseId());
        BrokerCase brokerCase = caseRepository.findByCaseNumber(resolvedCaseNumber).orElse(null);
        boolean created = false;

        if (brokerCase == null) {
            brokerCase = BrokerCase.builder()
                    .caseNumber(ensureUniqueCaseNumber(resolvedCaseNumber))
                    .build();
            brokerCase.setCompanyId(brokerCompanyId);
            created = true;
        } else if (!Objects.equals(brokerCase.getCompanyId(), brokerCompanyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "다른 회사의 케이스 번호와 충돌합니다.");
        }

        brokerCase.setClient(client);
        brokerCase.setStatus(req.getCaseStatus() == null ? CaseStatus.REGISTERED : req.getCaseStatus());
        brokerCase.setPaymentStatus(req.getPaymentStatus() == null ? PaymentStatus.UNPAID : req.getPaymentStatus());
        brokerCase.setShippingMethod(req.getShippingMethod() == null ? ShippingMethod.SEA : req.getShippingMethod());
        brokerCase.setBlNumber(req.getBlNumber());
        brokerCase.setEtaDate(req.getEtaDate());
        brokerCase.setAtaDate(req.getAtaDate());
        brokerCase.setDeparturePorts(req.getDeparturePorts());
        brokerCase.setArrivalPort(req.getArrivalPort());
        brokerCase.setTotalAmount(req.getTotalAmount());
        brokerCase.setDutyAmount(req.getDutyAmount());
        brokerCase.setVatAmount(req.getVatAmount());
        brokerCase.setBrokerageFee(req.getBrokerageFee());
        brokerCase.setMemo(req.getMemo());

        brokerCase.getCargos().clear();
        for (CarivSyncCargoRequest cargoReq : safeList(req.getCargos())) {
            CaseCargo cargo = CaseCargo.builder()
                    .itemName(cargoReq.getItemName())
                    .hsCode(cargoReq.getHsCode())
                    .quantity(cargoReq.getQuantity())
                    .unit(cargoReq.getUnit())
                    .unitPrice(cargoReq.getUnitPrice())
                    .totalPrice(cargoReq.getTotalPrice())
                    .weight(cargoReq.getWeight())
                    .originCountry(cargoReq.getOriginCountry())
                    .build();
            brokerCase.addCargo(cargo);
        }
        syncAttachments(brokerCase, req.getAttachments());

        BrokerCase savedCase = caseRepository.save(brokerCase);
        log.info("[CarivSync] synced case caseId={}, caseNumber={}, created={}, brokerCompanyId={}",
                savedCase.getId(), savedCase.getCaseNumber(), created, brokerCompanyId);

        return CarivSyncCaseResponse.builder()
                .brokerConnectionId(connection.getId())
                .brokerConnectionStatus(connection.getStatus())
                .clientId(client.getId())
                .clientCompanyName(client.getCompanyName())
                .clientExternalCode(client.getExternalCode())
                .caseId(savedCase.getId())
                .caseNumber(savedCase.getCaseNumber())
                .caseStatus(savedCase.getStatus())
                .created(created)
                .build();
    }

    @Transactional
    public CarivSyncCaseResponse syncCaseFromPush(
            CarivSyncCaseRequest req,
            String providedToken,
            HttpServletRequest httpRequest
    ) {
        assertPushAuthorized(httpRequest, providedToken);
        if (req == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 본문이 비어 있습니다.");
        }

        Long brokerCompanyId = req.getBrokerCompanyId();
        if (brokerCompanyId == null || brokerCompanyId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "brokerCompanyId is required");
        }

        Long exporterCompanyId = resolveExporterCompanyId(req.getExporterCompanyId());
        req.setExporterCompanyId(exporterCompanyId);
        boolean hasConnection = connectionRepository
                .findByExporterCompanyIdAndBrokerCompanyId(exporterCompanyId, brokerCompanyId)
                .isPresent();

        // First push should show up as a pending connection request in customs.
        if (!hasConnection && (req.getConnectionStatus() == null || req.getConnectionStatus() == ConnectionStatus.APPROVED)) {
            req.setConnectionStatus(ConnectionStatus.PENDING);
        }

        CarivSyncCaseResponse response = syncCase(brokerCompanyId, req);
        notificationService.notifyCasePushed(
                brokerCompanyId,
                response.getCaseId(),
                response.getCaseNumber(),
                response.getClientCompanyName(),
                response.isCreated()
        );
        return response;
    }

    public void assertPushAuthorized(HttpServletRequest request, String providedToken) {
        validatePushRequest(request, providedToken);
    }

    @Transactional
    public CarivDummySeedResponse seedDummyCases(Long brokerCompanyId, int caseCount) {
        int normalizedCount = Math.max(1, Math.min(caseCount, 20));

        BrokerConnection pending = createPendingConnectionForDemo(brokerCompanyId);
        List<CarivSyncCaseResponse> syncedCases = new ArrayList<>();
        for (int i = 1; i <= normalizedCount; i++) {
            syncedCases.add(syncCase(brokerCompanyId, buildDummyRequest(i)));
        }

        long pendingCount = connectionRepository.countByBrokerCompanyIdAndStatus(
                brokerCompanyId, ConnectionStatus.PENDING);

        log.info("[CarivSync] dummy seed complete brokerCompanyId={}, caseCount={}, pendingCount={}",
                brokerCompanyId, normalizedCount, pendingCount);

        return CarivDummySeedResponse.builder()
                .pendingConnectionId(pending.getId())
                .pendingConnectionCount(pendingCount)
                .syncedCases(syncedCases)
                .build();
    }

    private BrokerConnection upsertConnection(
            Long brokerCompanyId,
            Long exporterCompanyId,
            String exporterCompanyName,
            String exporterBusinessNumber,
            ConnectionStatus requestedStatus
    ) {
        String brokerCompanyName = resolveBrokerCompanyName(brokerCompanyId);
        BrokerConnection connection = connectionRepository
                .findByExporterCompanyIdAndBrokerCompanyId(exporterCompanyId, brokerCompanyId)
                .orElseGet(() -> connectionRepository.save(BrokerConnection.builder()
                        .exporterCompanyId(exporterCompanyId)
                        .brokerCompanyId(brokerCompanyId)
                        .brokerCompanyName(brokerCompanyName)
                        .exporterCompanyName(exporterCompanyName)
                        .exporterBusinessNumber(exporterBusinessNumber)
                        .build()));

        if (trimToNull(connection.getBrokerCompanyName()) == null && brokerCompanyName != null) {
            connection.updateBrokerCompanyName(brokerCompanyName);
        }
        connection.updateExporterProfile(exporterCompanyName, exporterBusinessNumber);

        ConnectionStatus targetStatus = requestedStatus == null ? ConnectionStatus.APPROVED : requestedStatus;
        if (targetStatus == ConnectionStatus.APPROVED && connection.getStatus() != ConnectionStatus.APPROVED) {
            connection.approve();
        } else if (targetStatus == ConnectionStatus.REJECTED && connection.getStatus() != ConnectionStatus.REJECTED) {
            connection.reject();
        }
        return connection;
    }

    private String resolveBrokerCompanyName(Long brokerCompanyId) {
        if (brokerCompanyId == null || brokerCompanyId <= 0L) {
            return "Cariv Partners 관세사";
        }
        return companyRepository.findById(brokerCompanyId)
                .map(company -> trimToNull(company.getCompanyName()))
                .orElse("Cariv Partners 관세사");
    }

    private BrokerClient upsertClient(
            Long brokerCompanyId,
            Long exporterCompanyId,
            String exporterCompanyName,
            String exporterBusinessNumber,
            String exporterPhoneNumber,
            String exporterEmail
    ) {
        String externalCode = String.valueOf(exporterCompanyId);
        BrokerClient client = clientRepository.findByCompanyIdAndExternalCode(brokerCompanyId, externalCode)
                .or(() -> exporterBusinessNumber == null || exporterBusinessNumber.isBlank()
                        ? java.util.Optional.empty()
                        : clientRepository.findByCompanyIdAndBusinessNumber(brokerCompanyId, exporterBusinessNumber))
                .or(() -> clientRepository.findByCompanyIdAndCompanyName(brokerCompanyId, exporterCompanyName))
                .orElseGet(() -> {
                    BrokerClient created = BrokerClient.builder()
                            .companyName(exporterCompanyName)
                            .externalCode(externalCode)
                            .businessNumber(exporterBusinessNumber)
                            .phoneNumber(exporterPhoneNumber)
                            .email(exporterEmail)
                            .active(true)
                            .build();
                    created.setCompanyId(brokerCompanyId);
                    return created;
                });

        if (trimToNull(exporterCompanyName) != null) {
            client.setCompanyName(exporterCompanyName.trim());
        }
        client.setExternalCode(externalCode);
        if (trimToNull(exporterBusinessNumber) != null) {
            client.setBusinessNumber(exporterBusinessNumber.trim());
        }
        if (trimToNull(exporterPhoneNumber) != null) {
            client.setPhoneNumber(exporterPhoneNumber.trim());
        }
        if (trimToNull(exporterEmail) != null) {
            client.setEmail(exporterEmail.trim());
        }
        client.setActive(true);
        return clientRepository.save(client);
    }

    private BrokerClient resolveClientForCase(
            BrokerConnection connection,
            Long brokerCompanyId,
            Long exporterCompanyId,
            String exporterCompanyName,
            String exporterBusinessNumber,
            String exporterPhoneNumber,
            String exporterEmail
    ) {
        BrokerClient linkedClient = findLinkedClient(connection, brokerCompanyId);
        if (linkedClient != null) {
            linkedClient.setExternalCode(String.valueOf(exporterCompanyId));
            if (trimToNull(exporterCompanyName) != null) {
                linkedClient.setCompanyName(exporterCompanyName.trim());
            }
            if (trimToNull(exporterBusinessNumber) != null) {
                linkedClient.setBusinessNumber(exporterBusinessNumber.trim());
            }
            if (trimToNull(exporterPhoneNumber) != null) {
                linkedClient.setPhoneNumber(exporterPhoneNumber.trim());
            }
            if (trimToNull(exporterEmail) != null) {
                linkedClient.setEmail(exporterEmail.trim());
            }
            linkedClient.setActive(true);
            return clientRepository.save(linkedClient);
        }

        BrokerClient resolved = upsertClient(
                brokerCompanyId,
                exporterCompanyId,
                exporterCompanyName,
                exporterBusinessNumber,
                exporterPhoneNumber,
                exporterEmail
        );

        if (resolved != null && connection != null && connection.getLinkedClientId() == null
                && connection.getStatus() == ConnectionStatus.APPROVED) {
            connection.linkClient(resolved.getId());
        }
        return resolved;
    }

    private BrokerClient findLinkedClient(BrokerConnection connection, Long brokerCompanyId) {
        if (connection == null || connection.getLinkedClientId() == null) {
            return null;
        }
        BrokerClient linked = clientRepository
                .findByIdAndCompanyId(connection.getLinkedClientId(), brokerCompanyId)
                .orElse(null);
        if (linked == null) {
            log.warn("[CarivSync] linked client not found. connectionId={}, linkedClientId={}, brokerCompanyId={}",
                    connection.getId(), connection.getLinkedClientId(), brokerCompanyId);
        }
        return linked;
    }

    private BrokerConnection createPendingConnectionForDemo(Long brokerCompanyId) {
        long randomExporterId = 990000L + ThreadLocalRandom.current().nextInt(10000, 99999);
        return connectionRepository.save(BrokerConnection.builder()
                .exporterCompanyId(randomExporterId)
                .brokerCompanyId(brokerCompanyId)
                .brokerCompanyName("Cariv Partners 관세사")
                .exporterCompanyName("카리브 더미수출사 " + randomExporterId)
                .status(ConnectionStatus.PENDING)
                .build());
    }

    private CarivSyncCaseRequest buildDummyRequest(int index) {
        LocalDate etaDate = LocalDate.now().plusDays(index);
        LocalDate ataDate = index > 2 ? etaDate.plusDays(1) : null;
        String suffix = String.format(Locale.ROOT, "%03d", index);

        CarivSyncCaseRequest req = new CarivSyncCaseRequest();
        req.setExternalCaseId("CARIV-SYNC-" + LocalDateTime.now().format(CASE_NUMBER_TS) + "-" + suffix);
        req.setExporterCompanyId(9001000L + index);
        req.setExporterCompanyName("카리브수출 더미상사 " + index);
        req.setExporterBusinessNumber("220-81-" + (73000 + index));
        req.setExporterPhoneNumber("02-555-" + (1200 + index));
        req.setExporterEmail("ops" + index + "@cariv-demo.kr");
        req.setConnectionStatus(ConnectionStatus.APPROVED);
        req.setShippingMethod(index % 2 == 0 ? ShippingMethod.AIR : ShippingMethod.SEA);
        req.setCaseStatus(switch (index % 4) {
            case 1 -> CaseStatus.REGISTERED;
            case 2 -> CaseStatus.IN_PROGRESS;
            case 3 -> CaseStatus.CUSTOMS_DECLARED;
            default -> CaseStatus.COMPLETED;
        });
        req.setPaymentStatus(index % 3 == 0 ? PaymentStatus.PAID : PaymentStatus.UNPAID);
        req.setBlNumber("BL-CARIV-" + suffix);
        req.setEtaDate(etaDate);
        req.setAtaDate(ataDate);
        req.setDeparturePorts("CNSHA (상해)");
        req.setArrivalPort("KRINC (인천)");
        req.setTotalAmount(new BigDecimal("45000000").add(BigDecimal.valueOf(index * 1_200_000L)));
        req.setDutyAmount(new BigDecimal("3600000").add(BigDecimal.valueOf(index * 110_000L)));
        req.setVatAmount(new BigDecimal("4680000").add(BigDecimal.valueOf(index * 130_000L)));
        req.setBrokerageFee(new BigDecimal("400000").add(BigDecimal.valueOf(index * 15_000L)));
        req.setMemo("Cariv 전송 더미 케이스 #" + index + " (피그마 상세 화면 테스트용)");
        req.setCargos(List.of(
                buildDummyCargo("브레이크 패드 세트 " + index, "8708.30", "EA", 800 + (index * 30), 54 + index, 280 + index),
                buildDummyCargo("엔진 오일 필터 " + index, "8421.23", "EA", 1200 + (index * 50), 9 + index, 95 + index)
        ));
        req.setAttachments(List.of(
                buildDummyAttachment("CUSTOMS_DECLARATION", "말소증명서-" + suffix + ".pdf", "s3://cariv-demo/malso/" + suffix + ".pdf", 83_642L, "application/pdf"),
                buildDummyAttachment("OTHER", "차주신분증-" + suffix + ".jpg", "s3://cariv-demo/owner-id/" + suffix + ".jpg", 51_204L, "image/jpeg")
        ));
        return req;
    }

    private CarivSyncCargoRequest buildDummyCargo(
            String itemName,
            String hsCode,
            String unit,
            int quantity,
            int unitPrice,
            int weight
    ) {
        CarivSyncCargoRequest cargo = new CarivSyncCargoRequest();
        cargo.setItemName(itemName);
        cargo.setHsCode(hsCode);
        cargo.setQuantity(BigDecimal.valueOf(quantity));
        cargo.setUnit(unit);
        cargo.setUnitPrice(BigDecimal.valueOf(unitPrice));
        cargo.setTotalPrice(BigDecimal.valueOf((long) quantity * unitPrice));
        cargo.setWeight(BigDecimal.valueOf(weight).setScale(3));
        cargo.setOriginCountry("CN");
        return cargo;
    }

    private CarivSyncAttachmentRequest buildDummyAttachment(
            String type,
            String fileName,
            String filePath,
            Long fileSize,
            String contentType
    ) {
        CarivSyncAttachmentRequest attachment = new CarivSyncAttachmentRequest();
        attachment.setType(type);
        attachment.setFileName(fileName);
        attachment.setFilePath(filePath);
        attachment.setFileSize(fileSize);
        attachment.setContentType(contentType);
        return attachment;
    }

    private Long resolveExporterCompanyId(Long exporterCompanyId) {
        if (exporterCompanyId != null) {
            return exporterCompanyId;
        }
        return 980000L + ThreadLocalRandom.current().nextInt(10000, 99999);
    }

    private String resolveCaseNumber(String externalCaseId) {
        if (externalCaseId != null && !externalCaseId.isBlank()) {
            return externalCaseId.trim();
        }
        return "CARIV-SYNC-" + LocalDateTime.now().format(CASE_NUMBER_TS);
    }

    private String ensureUniqueCaseNumber(String baseCaseNumber) {
        String candidate = baseCaseNumber;
        int suffix = 1;
        while (caseRepository.findByCaseNumber(candidate).isPresent()) {
            candidate = baseCaseNumber + "-" + suffix++;
        }
        return candidate;
    }

    private <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    private void syncAttachments(BrokerCase brokerCase, List<CarivSyncAttachmentRequest> attachmentRequests) {
        if (attachmentRequests == null) {
            return;
        }

        brokerCase.getAttachments().clear();
        for (CarivSyncAttachmentRequest attachmentReq : attachmentRequests) {
            if (attachmentReq == null) {
                continue;
            }

            String filePath = trimToNull(attachmentReq.getFilePath());
            if (filePath == null) {
                continue;
            }

            String fileName = firstNonBlank(
                    attachmentReq.getFileName(),
                    extractFileName(filePath),
                    "attachment"
            );

            CaseAttachment attachment = CaseAttachment.builder()
                    .type(resolveAttachmentType(attachmentReq.getType()))
                    .fileName(fileName)
                    .filePath(filePath)
                    .fileSize(attachmentReq.getFileSize())
                    .contentType(trimToNull(attachmentReq.getContentType()))
                    .build();
            brokerCase.addAttachment(attachment);
        }
    }

    private AttachmentType resolveAttachmentType(String rawType) {
        String normalized = trimToNull(rawType);
        if (normalized == null) {
            return AttachmentType.OTHER;
        }
        String enumName = normalized
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        try {
            return AttachmentType.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            return AttachmentType.OTHER;
        }
    }

    private String extractFileName(String filePath) {
        String normalized = trimToNull(filePath);
        if (normalized == null) {
            return null;
        }
        String trimmed = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 && slash + 1 < trimmed.length() ? trimmed.substring(slash + 1) : trimmed;
    }

    private void validatePushRequest(HttpServletRequest request, String providedToken) {
        validateHttpsTransport(request);
        validateSourceIp(request);
        validatePushToken(providedToken);
    }

    private void validatePushToken(String providedToken) {
        String expectedPrimary = trimToNull(pushAgentToken);
        String expectedSecondary = trimToNull(previousPushAgentToken);
        if (expectedPrimary == null && expectedSecondary == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "cariv sync push is not configured");
        }

        String provided = trimToNull(providedToken);
        if (provided == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "missing X-Agent-Token header");
        }

        boolean matchedPrimary = expectedPrimary != null && MessageDigest.isEqual(
                expectedPrimary.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        boolean matchedSecondary = expectedSecondary != null && MessageDigest.isEqual(
                expectedSecondary.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        boolean matched = matchedPrimary || matchedSecondary;
        if (!matched) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "invalid agent token");
        }
    }

    private void validateHttpsTransport(HttpServletRequest request) {
        if (!requireHttpsForPush) {
            return;
        }
        if (request == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "https request is required");
        }

        String forwardedProto = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(trimToNull(forwardedProto));
        if (!secure) {
            throw new CustomException(ErrorCode.FORBIDDEN, "https request is required");
        }
    }

    private void validateSourceIp(HttpServletRequest request) {
        List<String> rules = parseAllowedIpRules();
        if (rules.isEmpty()) {
            return;
        }
        if (request == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "source ip validation failed");
        }

        String clientIp = extractClientIp(request);
        if (clientIp == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "source ip validation failed");
        }

        boolean allowed = rules.stream().anyMatch(rule -> matchesIpRule(clientIp, rule));
        if (!allowed) {
            throw new CustomException(ErrorCode.FORBIDDEN, "source ip is not allowed");
        }
    }

    private List<String> parseAllowedIpRules() {
        String raw = trimToNull(allowedPushIpsRaw);
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = firstForwardedValue(request.getHeader("X-Forwarded-For"));
        String xRealIp = trimToNull(request.getHeader("X-Real-IP"));
        String remoteAddr = trimToNull(request.getRemoteAddr());
        return normalizeIpToken(firstNonBlank(xForwardedFor, xRealIp, remoteAddr));
    }

    private String firstForwardedValue(String headerValue) {
        String normalized = trimToNull(headerValue);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split(",");
        return parts.length == 0 ? null : trimToNull(parts[0]);
    }

    private String normalizeIpToken(String token) {
        String value = trimToNull(token);
        if (value == null) {
            return null;
        }

        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']')).trim();
        }
        if (value.chars().filter(ch -> ch == ':').count() == 1 && value.contains(".")) {
            int idx = value.lastIndexOf(':');
            if (idx > 0) {
                return value.substring(0, idx).trim();
            }
        }
        return value;
    }

    private boolean matchesIpRule(String clientIp, String rule) {
        if (rule.contains("/")) {
            return isIpInCidr(clientIp, rule);
        }
        return clientIp.equals(rule);
    }

    private boolean isIpInCidr(String ip, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) {
            return false;
        }
        String networkPart = trimToNull(parts[0]);
        String prefixPart = trimToNull(parts[1]);
        if (networkPart == null || prefixPart == null) {
            return false;
        }

        try {
            InetAddress ipAddress = InetAddress.getByName(ip);
            InetAddress networkAddress = InetAddress.getByName(networkPart);
            byte[] ipBytes = ipAddress.getAddress();
            byte[] networkBytes = networkAddress.getAddress();
            if (ipBytes.length != networkBytes.length) {
                return false;
            }

            int prefix = Integer.parseInt(prefixPart);
            int maxPrefix = ipBytes.length * 8;
            if (prefix < 0 || prefix > maxPrefix) {
                return false;
            }

            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            if (remainingBits == 0) {
                return true;
            }

            int mask = 0xFF << (8 - remainingBits);
            return (ipBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
