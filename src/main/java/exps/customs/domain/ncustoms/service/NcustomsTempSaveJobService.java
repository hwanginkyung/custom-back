package exps.customs.domain.ncustoms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import exps.customs.domain.brokercase.entity.BrokerCase;
import exps.customs.domain.brokercase.repository.BrokerCaseRepository;
import exps.customs.domain.ncustoms.dto.CreateNcustomsContainerTempSaveRequest;
import exps.customs.domain.ncustoms.dto.NcustomsContainerTempSaveResponse;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobClaimItemResponse;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobClaimResponse;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobClaimRequest;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobCompleteRequest;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobCreateResponse;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobStatus;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobStatusResponse;
import exps.customs.domain.notification.service.BrokerNotificationService;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NcustomsTempSaveJobService {

    private static final String JOB_SEQ_KEY = "ncustoms:temp-save:job-seq";
    private static final String JOB_KEY_PREFIX = "ncustoms:temp-save:job:";
    private static final String QUEUE_KEY_PREFIX = "ncustoms:temp-save:queue:";
    private static final Duration JOB_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BrokerNotificationService notificationService;
    private final BrokerCaseRepository brokerCaseRepository;

    @Value("${ncustoms.temp-save.agent-token:}")
    private String agentToken;

    public NcustomsTempSaveJobService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            BrokerNotificationService notificationService,
            BrokerCaseRepository brokerCaseRepository
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.brokerCaseRepository = brokerCaseRepository;
    }

    @Transactional
    public NcustomsTempSaveJobCreateResponse createJob(
            Long companyId,
            Long requesterUserId,
            Long caseId,
            CreateNcustomsContainerTempSaveRequest tempSaveRequest
    ) {
        if (companyId == null || companyId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "companyId is required");
        }
        if (tempSaveRequest == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "temp save request is required");
        }

        Long jobId = redis.opsForValue().increment(JOB_SEQ_KEY);
        if (jobId == null || jobId <= 0L) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to allocate temp-save job id");
        }

        TempSaveJobRecord record = new TempSaveJobRecord();
        record.setJobId(jobId);
        record.setCompanyId(companyId);
        record.setCaseId(caseId);
        record.setRequesterUserId(requesterUserId);
        record.setStatus(NcustomsTempSaveJobStatus.PENDING.name());
        record.setPayloadJson(writeJson(tempSaveRequest));
        record.setCreatedAt(Instant.now());
        record.setAttempts(0);

        saveRecord(record);
        redis.opsForList().rightPush(queueKey(companyId), String.valueOf(jobId));

        return new NcustomsTempSaveJobCreateResponse(jobId, record.getStatus(), record.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public NcustomsTempSaveJobStatusResponse getJobStatus(Long companyId, Long jobId) {
        TempSaveJobRecord record = loadRecord(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "temp-save job not found"));
        if (!record.getCompanyId().equals(companyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "temp-save job access denied");
        }
        return toStatusResponse(record);
    }

    @Transactional
    public NcustomsTempSaveJobClaimResponse claimJobs(NcustomsTempSaveJobClaimRequest request, String providedToken) {
        validateAgentToken(providedToken);
        if (request.getCompanyId() == null || request.getCompanyId() <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "companyId is required");
        }
        int limit = request.getLimit() == null ? 1 : Math.max(1, Math.min(20, request.getLimit()));
        String workerId = trimToNull(request.getWorkerId());

        List<NcustomsTempSaveJobClaimItemResponse> claimed = new ArrayList<>();
        String queueKey = queueKey(request.getCompanyId());
        for (int i = 0; i < limit; i++) {
            String rawJobId = redis.opsForList().leftPop(queueKey);
            if (rawJobId == null) {
                break;
            }

            Long jobId = parseJobId(rawJobId);
            if (jobId == null) {
                continue;
            }

            Optional<TempSaveJobRecord> loaded = loadRecord(jobId);
            if (loaded.isEmpty()) {
                continue;
            }

            TempSaveJobRecord record = loaded.get();
            if (!request.getCompanyId().equals(record.getCompanyId())) {
                continue;
            }
            if (!NcustomsTempSaveJobStatus.PENDING.name().equals(record.getStatus())) {
                continue;
            }

            record.setStatus(NcustomsTempSaveJobStatus.PROCESSING.name());
            record.setStartedAt(Instant.now());
            record.setWorkerId(workerId);
            record.setAttempts((record.getAttempts() == null ? 0 : record.getAttempts()) + 1);
            saveRecord(record);

            claimed.add(new NcustomsTempSaveJobClaimItemResponse(
                    record.getJobId(),
                    record.getCompanyId(),
                    record.getCaseId(),
                    readJsonNode(record.getPayloadJson()),
                    record.getCreatedAt(),
                    record.getAttempts()
            ));
        }

        return new NcustomsTempSaveJobClaimResponse(claimed);
    }

    @Transactional
    public NcustomsTempSaveJobStatusResponse completeJob(
            Long jobId,
            NcustomsTempSaveJobCompleteRequest request,
            String providedToken
    ) {
        validateAgentToken(providedToken);
        TempSaveJobRecord record = loadRecord(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "temp-save job not found"));

        boolean success = Boolean.TRUE.equals(request.getSuccess());
        record.setStatus(success ? NcustomsTempSaveJobStatus.SUCCEEDED.name() : NcustomsTempSaveJobStatus.FAILED.name());
        record.setFinishedAt(Instant.now());
        record.setWorkerId(firstNonBlank(request.getWorkerId(), record.getWorkerId()));
        record.setErrorMessage(success ? null : firstNonBlank(request.getErrorMessage(), "ncustoms temp save failed"));
        record.setResultJson(success && request.getResult() != null ? writeJson(request.getResult()) : null);
        saveRecord(record);

        String caseNumber = null;
        if (record.getCaseId() != null) {
            caseNumber = brokerCaseRepository.findById(record.getCaseId())
                    .map(BrokerCase::getCaseNumber)
                    .orElse(null);
        }

        notificationService.notifyNcustomsTempSaveResult(
                record.getCompanyId(),
                record.getCaseId(),
                caseNumber,
                success,
                record.getErrorMessage()
        );

        return toStatusResponse(record);
    }

    private NcustomsTempSaveJobStatusResponse toStatusResponse(TempSaveJobRecord record) {
        NcustomsContainerTempSaveResponse result = null;
        if (record.getResultJson() != null && !record.getResultJson().isBlank()) {
            try {
                result = objectMapper.readValue(record.getResultJson(), NcustomsContainerTempSaveResponse.class);
            } catch (Exception ignored) {
                result = null;
            }
        }
        return new NcustomsTempSaveJobStatusResponse(
                record.getJobId(),
                record.getStatus(),
                record.getCaseId(),
                record.getCreatedAt(),
                record.getStartedAt(),
                record.getFinishedAt(),
                record.getAttempts(),
                record.getErrorMessage(),
                result
        );
    }

    private Optional<TempSaveJobRecord> loadRecord(Long jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        String raw = redis.opsForValue().get(jobKey(jobId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, TempSaveJobRecord.class));
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to parse temp-save job record");
        }
    }

    private void saveRecord(TempSaveJobRecord record) {
        String json = writeJson(record);
        String key = jobKey(record.getJobId());
        redis.opsForValue().set(key, json, JOB_TTL);
    }

    private void validateAgentToken(String providedToken) {
        String expected = trimToNull(agentToken);
        if (expected == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "ncustoms temp-save agent token is not configured");
        }
        String provided = trimToNull(providedToken);
        if (provided == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "missing X-Agent-Token header");
        }
        boolean matched = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "invalid agent token");
        }
    }

    private JsonNode readJsonNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to parse temp-save payload");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to serialize temp-save payload");
        }
    }

    private Long parseJobId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String jobKey(Long jobId) {
        return JOB_KEY_PREFIX + jobId;
    }

    private String queueKey(Long companyId) {
        return QUEUE_KEY_PREFIX + companyId;
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

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TempSaveJobRecord {
        private Long jobId;
        private Long companyId;
        private Long caseId;
        private Long requesterUserId;
        private String status;
        private String payloadJson;
        private String resultJson;
        private String errorMessage;
        private String workerId;
        private Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private Integer attempts;
    }
}
