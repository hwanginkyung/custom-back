package exps.customs.domain.login.repository;

import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLoginId(String loginId);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByLoginId(String loginId);
    boolean existsByEmailIgnoreCase(String email);
    List<User> findAllByCompanyId(Long companyId);
    List<User> findAllByCompanyIdAndRole(Long companyId, Role role);
    List<User> findAllByCompanyIdAndRoleAndActiveTrue(Long companyId, Role role);
    List<User> findAllByRoleAndActiveTrue(Role role);
    Optional<User> findByIdAndCompanyIdAndRoleAndActiveTrue(Long id, Long companyId, Role role);

    @Query("""
            select distinct u
            from User u
            where u.active = true
              and u.role in :roles
              and u.ncustomsUserCode is not null and length(trim(u.ncustomsUserCode)) > 0
              and u.ncustomsWriterId is not null and length(trim(u.ncustomsWriterId)) > 0
              and u.ncustomsWriterName is not null and length(trim(u.ncustomsWriterName)) > 0
            """)
    List<User> findActiveBrokerUsersWithNcustomsProfile(@Param("roles") Collection<Role> roles);
}
