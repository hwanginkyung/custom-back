package exps.cariv.domain.customs.dto.request;

import java.time.LocalDate;

/**
 * 신고필증 목록 조회 요청.
 */
public record CustomsListRequest(
        String stage,           // CustomsStatus 이름 or null (WAITING / IN_PROGRESS / DONE)
        String shipperName,
        String query,
        LocalDate from,
        LocalDate to,
        int page,
        int size
) {
    public CustomsListRequest {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
    }
}
