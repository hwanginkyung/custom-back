package exps.customs.domain.notification.service;

import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.notification.dto.BrokerNotificationResponse;
import exps.customs.domain.notification.dto.BrokerNotificationSummaryResponse;
import exps.customs.domain.notification.entity.BrokerNotification;
import exps.customs.domain.notification.entity.BrokerNotificationType;
import exps.customs.domain.notification.repository.BrokerNotificationRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BrokerNotificationService {

    private final BrokerNotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public BrokerNotificationSummaryResponse getSummary(Long companyId) {
        return new BrokerNotificationSummaryResponse(
                notificationRepository.countByCompanyIdAndReadAtIsNull(companyId),
                listRecent(companyId)
        );
    }

    @Transactional(readOnly = true)
    public List<BrokerNotificationResponse> listRecent(Long companyId) {
        return notificationRepository.findTop20ByCompanyIdOrderByCreatedAtDesc(companyId)
                .stream()
                .map(BrokerNotificationResponse::from)
                .toList();
    }

    @Transactional
    public void markRead(Long companyId, Long notificationId) {
        BrokerNotification notification = notificationRepository.findByIdAndCompanyId(notificationId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다."));
        notification.markRead();
    }

    @Transactional
    public void markAllRead(Long companyId) {
        notificationRepository.markAllRead(companyId, Instant.now());
    }

    @Transactional
    public void notifyConnectionRequested(BrokerConnection connection) {
        if (connection == null || connection.getBrokerCompanyId() == null) {
            return;
        }
        String exporterName = firstNonBlank(connection.getExporterCompanyName(), "수출사");
        notificationRepository.save(BrokerNotification.builder()
                .companyId(connection.getBrokerCompanyId())
                .type(BrokerNotificationType.CONNECTION_REQUEST)
                .title("새 연동 요청")
                .message(exporterName + "에서 관세사 연동을 요청했습니다.")
                .linkPath("/mypage")
                .sourceId(connection.getId())
                .build());
    }

    @Transactional
    public void notifyCasePushed(
            Long brokerCompanyId,
            Long caseId,
            String caseNumber,
            String clientCompanyName,
            boolean created
    ) {
        if (brokerCompanyId == null || brokerCompanyId <= 0L || caseId == null) {
            return;
        }
        String label = firstNonBlank(caseNumber, "케이스");
        String client = firstNonBlank(clientCompanyName, "수출사");
        notificationRepository.save(BrokerNotification.builder()
                .companyId(brokerCompanyId)
                .type(BrokerNotificationType.CASE_PUSH)
                .title(created ? "새 케이스 도착" : "케이스 업데이트")
                .message(client + "에서 " + label + " 정보를 보냈습니다.")
                .linkPath("/cases/" + caseId)
                .sourceId(caseId)
                .build());
    }

    @Transactional
    public void notifyNcustomsTempSaveResult(
            Long companyId,
            Long caseId,
            String caseNumber,
            boolean success,
            String errorMessage
    ) {
        if (companyId == null || companyId <= 0L) {
            return;
        }

        String caseLabel = firstNonBlank(caseNumber, caseId == null ? "케이스" : "케이스 #" + caseId);
        String message = success
                ? caseLabel + " 통관프로그램 임시저장이 완료되었습니다."
                : caseLabel + " 통관프로그램 임시저장에 실패했습니다."
                + (firstNonBlank(errorMessage) == null ? "" : " (" + errorMessage + ")");

        notificationRepository.save(BrokerNotification.builder()
                .companyId(companyId)
                .type(BrokerNotificationType.NCUSTOMS_TEMP_SAVE)
                .title(success ? "통관 임시저장 완료" : "통관 임시저장 실패")
                .message(message)
                .linkPath(caseId == null ? "/cases" : "/cases/" + caseId)
                .sourceId(caseId)
                .build());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }
}
