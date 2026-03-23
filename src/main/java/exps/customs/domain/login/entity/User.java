package exps.customs.domain.login.entity;

import exps.customs.domain.login.entity.enumType.Role;
import exps.customs.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "users")
public class User extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String loginId;

    @Column(length = 100)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
}
