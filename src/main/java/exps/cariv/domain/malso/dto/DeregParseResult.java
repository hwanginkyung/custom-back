package exps.cariv.domain.malso.dto;

import java.util.List;

public record DeregParseResult(
        ParsedDereg parsed,
        List<String> missingFields,
        List<String> errorFields
) {}
