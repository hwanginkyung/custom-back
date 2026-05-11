package exps.customs.domain.brokercase.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CaseAttachmentFileResponse {
    private final byte[] bytes;
    private final String fileName;
    private final String contentType;
}
