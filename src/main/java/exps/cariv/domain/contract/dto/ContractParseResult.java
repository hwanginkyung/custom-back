package exps.cariv.domain.contract.dto;

import java.util.List;

public record ContractParseResult(
        ContractParsed parsed,
        List<String> missingFields,
        List<String> errorFields
) {}
