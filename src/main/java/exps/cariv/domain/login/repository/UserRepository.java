package exps.cariv.domain.login.repository;


import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLoginId(String loginId);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByLoginId(String loginId);
    boolean existsByEmailIgnoreCase(String email);
    List<User> findAllByCompanyIdAndRole(Long companyId, Role role);
    List<User> findAllByCompanyIdAndRoleAndActiveTrue(Long companyId, Role role);
    Optional<User> findByIdAndCompanyIdAndRoleAndActiveTrue(Long id, Long companyId, Role role);
}
