package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "NCustoms profile option list")
public record NcustomsProfileOptionsResponse(
        List<UserCodeOption> userCodes,
        List<WriterOption> writers
) {
    public record UserCodeOption(
            String userCode,
            String userName
    ) {
    }

    public record WriterOption(
            String writerId,
            String writerName
    ) {
    }
}
