package exps.cariv.domain.registration.dto;

import java.util.List;

public record RegistrationParseResult(
        RegistrationParsed parsed,
        List<String> missingFields,
        List<String> errorFields
) {}
