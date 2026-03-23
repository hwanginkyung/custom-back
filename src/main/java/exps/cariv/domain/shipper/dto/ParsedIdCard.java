package exps.cariv.domain.shipper.dto;

public record ParsedIdCard(
        String holderName,
        String idNumber,
        String idAddress,
        String issueDate
) {}
