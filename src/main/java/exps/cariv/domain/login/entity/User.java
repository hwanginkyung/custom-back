package exps.cariv.domain.login.entity;


import exps.cariv.domain.login.entity.enumType.Role;
import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "users")
public class User extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    //회사명
    /*@ManyToOne(fetch = FetchType.LAZY)
    private Company company;*/
    // 로그인용 아이디
    @Column(nullable = false, unique = true, length = 100)
    private String loginId;
    //이메일?
    @Column(length = 100)
    private String email;
    //계정 비밀번호(Hash)
    @Column(nullable = false)
    private String passwordHash;

    boolean active;
    //관리자 or 직원
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // ✅ 비밀번호 변경 도메인 메서드
    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    // ✅ 계정 비활성화(삭제 대신)
    public void deactivate() {
        this.active = false;
    }

}
