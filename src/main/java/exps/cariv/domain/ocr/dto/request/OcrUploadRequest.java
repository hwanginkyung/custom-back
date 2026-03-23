package exps.cariv.domain.ocr.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.web.multipart.MultipartFile;

@Getter @Setter
public class OcrUploadRequest {
    @NotNull
    private MultipartFile document;
}
