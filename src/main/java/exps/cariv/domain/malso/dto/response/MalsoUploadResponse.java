package exps.cariv.domain.malso.dto.response;

public record MalsoUploadResponse(
        Long documentId,
        String s3Key,
        Long jobId
) {}
