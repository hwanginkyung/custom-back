package exps.cariv.domain.upstage.dto;

public record OcrRequest (
     String fileUrl // S3 presigned URL or local file upload → base64 가능
)
{}
