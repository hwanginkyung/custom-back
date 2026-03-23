package exps.cariv.domain.upstage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Content(
        String html,
        String markdown,
        String text
) {}
