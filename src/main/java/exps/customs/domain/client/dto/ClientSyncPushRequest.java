package exps.customs.domain.client.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ClientSyncPushRequest {

    @NotNull(message = "companyId is required")
    private Long companyId;

    @Valid
    private List<ClientSyncPushItem> items = new ArrayList<>();

    private String source;
    private String checkpoint;
}
