package exps.customs.global.security;

import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

@Getter
public class CustomUserDetails implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final User user;

    public CustomUserDetails(User user) { this.user = user; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return switch (user.getRole()) {
            case MASTER -> List.of(
                    new SimpleGrantedAuthority("ROLE_MASTER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_STAFF")
            );
            case ADMIN -> List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_STAFF")
            );
            default -> List.of(new SimpleGrantedAuthority("ROLE_STAFF"));
        };
    }

    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getLoginId(); }
    public Role getRole() { return user.getRole(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActive(); }
    public Long getUserId() { return user.getId(); }
    public Long getCompanyId() { return user.getCompanyId(); }
}
