package exps.customs.domain.brokercase.dto;

import exps.customs.domain.brokercase.entity.AttachmentType;
import exps.customs.domain.brokercase.entity.CaseAttachment;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class CaseAttachmentResponse {

    private final Long id;
    private final AttachmentType type;
    private final String typeLabel;
    private final String fileName;
    private final Long fileSize;
    private final String contentType;
    private final Instant createdAt;

    public static CaseAttachmentResponse from(CaseAttachment attachment) {
        return CaseAttachmentResponse.builder()
                .id(attachment.getId())
                .type(attachment.getType())
                .typeLabel(toLabel(attachment.getType()))
                .fileName(attachment.getFileName())
                .fileSize(attachment.getFileSize())
                .contentType(attachment.getContentType())
                .createdAt(attachment.getCreatedAt())
                .build();
    }

    private static String toLabel(AttachmentType type) {
        if (type == null) {
            return "기타 첨부";
        }
        return switch (type) {
            case INVOICE -> "인보이스";
            case PACKING_LIST -> "패킹리스트";
            case BL -> "B/L";
            case CUSTOMS_DECLARATION -> "신고필증";
            case CERTIFICATE_OF_ORIGIN -> "원산지 증명서";
            case INSPECTION_REPORT -> "검사 리포트";
            case OTHER -> "기타 첨부";
        };
    }
}
