package exps.cariv.domain.customs.dto.response;

import java.time.Instant;

/**
 * 관세사 전송(mock) 응답.
 */
public record CustomsMockSendResponse(
        Long customsRequestId,
        String status,
        String mode,
        Instant sentAt
) {}
