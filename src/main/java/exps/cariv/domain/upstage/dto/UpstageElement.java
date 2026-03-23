package exps.cariv.domain.upstage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstageElement(
        String category,         // "table", "paragraph", ...
        Content content,
        //List<Coordinate> coordinates,
        Integer id,
        Integer page
) {}

