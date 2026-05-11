package exps.customs;

import exps.customs.domain.login.entity.Company;
import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import exps.customs.domain.login.repository.CompanyRepository;
import exps.customs.domain.login.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class ProdAdminInitializer implements CommandLineRunner {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap-admin.enabled:false}")
    private boolean enabled;

    @Value("${app.bootstrap-admin.email:}")
    private String adminEmail;

    @Value("${app.bootstrap-admin.password:}")
    private String adminPassword;

    @Value("${app.bootstrap-admin.role:ADMIN}")
    private String adminRole;

    @Value("${app.bootstrap-admin.company-name:진솔 관세법인}")
    private String companyName;

    @Value("${app.bootstrap-admin.business-number:123-45-67890}")
    private String businessNumber;

    @Value("${app.bootstrap-admin.reset-password-on-startup:true}")
    private boolean resetPasswordOnStartup;

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("[ProdAdmin] bootstrap disabled");
            return;
        }

        String email = normalizeEmail(adminEmail);
        if (email.isBlank()) {
            log.warn("[ProdAdmin] bootstrap skipped: app.bootstrap-admin.email is empty");
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("[ProdAdmin] bootstrap skipped: app.bootstrap-admin.password is empty");
            return;
        }

        Company company = resolveCompany();
        Role role = resolveRole(adminRole);

        User user = userRepository.findByEmailIgnoreCase(email)
                .or(() -> userRepository.findByLoginId(email))
                .orElse(null);

        if (user == null) {
            User newAdmin = User.builder()
                    .loginId(email)
                    .email(email)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .active(true)
                    .role(role)
                    .build();
            newAdmin.setCompanyId(company.getId());
            userRepository.save(newAdmin);
            log.info("[ProdAdmin] created bootstrap admin email={}, role={}, companyId={}",
                    email, role, company.getId());
            return;
        }

        user.setLoginId(email);
        user.setEmail(email);
        user.setActive(true);
        user.setRole(role);
        if (user.getCompanyId() == null) {
            user.setCompanyId(company.getId());
        }
        if (resetPasswordOnStartup) {
            user.setPasswordHash(passwordEncoder.encode(adminPassword));
        }
        userRepository.save(user);
        log.info("[ProdAdmin] updated bootstrap admin email={}, role={}, companyId={}, passwordReset={}",
                email, role, user.getCompanyId(), resetPasswordOnStartup);
    }

    private Company resolveCompany() {
        List<Company> companies = companyRepository.findAll();
        if (!companies.isEmpty()) {
            return companies.get(0);
        }
        Company company = Company.builder()
                .companyName(companyName)
                .businessNumber(businessNumber)
                .build();
        return companyRepository.save(company);
    }

    private Role resolveRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return Role.ADMIN;
        }
        try {
            return Role.valueOf(rawRole.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[ProdAdmin] invalid role '{}', fallback to ADMIN", rawRole);
            return Role.ADMIN;
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
