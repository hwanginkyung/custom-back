package exps.customs.domain.client.dto;

import exps.customs.domain.client.entity.BrokerClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@Schema(description = "화주 응답 DTO")
public class ClientResponse {

    @Schema(description = "화주 ID")
    private final Long id;

    @Schema(description = "회사명")
    private final String companyName;

    @Schema(description = "외부(통관 프로그램) 코드")
    private final String clientCode;

    @Schema(description = "대표자명")
    private final String representativeName;

    @Schema(description = "사업자등록번호")
    private final String businessNumber;

    @Schema(description = "통관고유부호")
    private final String customsUniqueCode;

    @Schema(description = "식별부호")
    private final String identifierCode;

    @Schema(description = "전화번호")
    private final String phoneNumber;

    @Schema(description = "이메일")
    private final String email;

    @Schema(description = "주소")
    private final String address;

    @Schema(description = "메모")
    private final String memo;

    @Schema(description = "활성 여부")
    private final boolean active;

    @Schema(description = "통관 프로그램 동기화 여부")
    private final boolean synced;

    @Schema(description = "생성일")
    private final Instant createdAt;

    @Schema(description = "수정일")
    private final Instant updatedAt;

    public static ClientResponse from(BrokerClient client) {
        return ClientResponse.builder()
                .id(client.getId())
                .companyName(client.getCompanyName())
                .clientCode(client.getExternalCode())
                .representativeName(client.getRepresentativeName())
                .businessNumber(client.getBusinessNumber())
                .customsUniqueCode(client.getCustomsUniqueCode())
                .identifierCode(client.getIdentifierCode())
                .phoneNumber(client.getPhoneNumber())
                .email(client.getEmail())
                .address(client.getAddress())
                .memo(client.getMemo())
                .active(client.isActive())
                .synced(client.getExternalCode() != null && !client.getExternalCode().isBlank())
                .createdAt(client.getCreatedAt())
                .updatedAt(client.getUpdatedAt())
                .build();
    }
}
