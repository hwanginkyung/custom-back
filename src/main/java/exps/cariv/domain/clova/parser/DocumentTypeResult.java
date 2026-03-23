package exps.cariv.domain.clova.parser;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTypeResult {
    private DocumentType documentType;
    private double score;
    private String reason;
}
