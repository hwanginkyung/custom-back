package exps.customs.domain.login.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreateStaffRequest {
    @NotBlank
    private String loginId;

    @NotBlank
    @Size(min = 8, max = 100)
    private String password;

    @Email
    private String email;
}
