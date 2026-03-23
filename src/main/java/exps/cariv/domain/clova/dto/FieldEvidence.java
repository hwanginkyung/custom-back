package exps.cariv.domain.clova.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Field extraction trace for operations/debugging.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldEvidence {
    private String value;
    private Double confidence;
    private String anchorText;
    private EvidenceBox anchorBox;
    private String valueText;
    private EvidenceBox valueBox;
    private List<String> candidates;
}
