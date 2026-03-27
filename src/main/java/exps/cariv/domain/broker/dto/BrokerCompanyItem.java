package exps.cariv.domain.broker.dto;

/** 연동 가능한 관세사 회사 (드롭다운 표시용) */
public record BrokerCompanyItem(
        Long companyId,
        String companyName
) {}
