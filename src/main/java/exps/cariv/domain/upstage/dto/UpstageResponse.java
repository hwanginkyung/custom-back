package exps.cariv.domain.upstage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstageResponse(
        List<UpstageElement> elements
) {}
