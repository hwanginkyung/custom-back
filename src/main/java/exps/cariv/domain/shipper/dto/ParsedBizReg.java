package exps.cariv.domain.shipper.dto;

public record ParsedBizReg(
        String companyName,
        String representativeName,
        String bizNumber,
        String bizType,
        String bizCategory,
        String bizAddress,
        String openDate
) {}
