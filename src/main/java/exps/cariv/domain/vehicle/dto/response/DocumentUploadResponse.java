package exps.cariv.domain.vehicle.dto.response;

/**
 * 문서 업로드(reg/auction/contract) 응답.
 */
public record DocumentUploadResponse(
        Long documentId,
        String s3Key,
        Long jobId          // OCR job id (자동차등록증만 해당, 나머지는 null)
) {}
