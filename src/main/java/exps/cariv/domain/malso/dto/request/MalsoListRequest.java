package exps.cariv.domain.malso.dto.request;

import java.time.LocalDate;

public record MalsoListRequest(
        String stage,
        String shipperName,
        LocalDate startDate,
        LocalDate endDate,
        int page,
        int size
) {
    public MalsoListRequest {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
    }
}
