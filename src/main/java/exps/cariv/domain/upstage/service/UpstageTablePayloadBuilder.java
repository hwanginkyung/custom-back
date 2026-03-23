package exps.cariv.domain.upstage.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.upstage.dto.Content;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class UpstageTablePayloadBuilder {
    private UpstageTablePayloadBuilder() {
    }

    public static String buildTableHtmlJson(ObjectMapper mapper, UpstageResponse res) throws JsonProcessingException {
        List<String> tables = Optional.ofNullable(res)
                .map(UpstageResponse::elements)
                .orElse(Collections.emptyList())
                .stream()
                .filter(element -> "table".equalsIgnoreCase(element.category()))
                .map(UpstageElement::content)
                .filter(Objects::nonNull)
                .map(Content::html)
                .filter(Objects::nonNull)
                .filter(html -> !html.isBlank())
                .toList();

        return mapper.writeValueAsString(Map.of("tables", tables));
    }
}
