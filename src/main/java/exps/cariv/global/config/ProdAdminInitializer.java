package exps.cariv.global.config;

import exps.cariv.domain.customs.entity.CustomsBroker;
import exps.cariv.domain.customs.repository.CustomsBrokerRepository;
import exps.cariv.domain.login.entity.Company;
import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import exps.cariv.domain.login.repository.CompanyRepository;
import exps.cariv.domain.login.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class ProdAdminInitializer {

    private static final String DEFAULT_PASSWORD = "admin1234!";

    private static final CustomsBrokerSeed DEFAULT_CUSTOMS_BROKER = new CustomsBrokerSeed(
            "진솔관세법인",
            "02-555-0101",
            "110-81-10001",
            "jinsol-customs@demo.local"
    );

    private static final List<AdminSeed> PROD_ADMIN_SEEDS = List.of(
            new AdminSeed("admin2", "admin2@cariv.local", "Cariv Admin2 Company", "Admin2 Owner"),
            new AdminSeed("admin3", "admin3@cariv.local", "Cariv Admin3 Company", "Admin3 Owner")
    );

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CustomsBrokerRepository customsBrokerRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public CommandLineRunner seedProdAdminUsers() {
        return args -> {
            int createdAdmins = 0;
            int updatedAdmins = 0;
            int changedBrokers = 0;

            for (AdminSeed seed : PROD_ADMIN_SEEDS) {
                Company company = resolveOrCreateCompany(seed.companyName(), seed.ownerName());
                UpsertResult result = upsertAdmin(seed, company.getId());
                if (result == UpsertResult.CREATED) {
                    createdAdmins++;
                } else if (result == UpsertResult.UPDATED) {
                    updatedAdmins++;
                }

                int brokerChanges = seedCustomsBrokers(company.getId());
                changedBrokers += brokerChanges;

                log.info("[ProdAdminInitializer] ensured admin loginId={} companyId={} companyName={} adminResult={} brokerChanges={}",
                        seed.loginId(),
                        company.getId(),
                        company.getName(),
                        result,
                        brokerChanges);
            }

            log.info("[ProdAdminInitializer] completed targetAdmins={} created={} updated={} brokerChanges={}",
                    PROD_ADMIN_SEEDS.stream().map(AdminSeed::email).toList(),
                    createdAdmins,
                    updatedAdmins,
                    changedBrokers);
        };
    }

    private Company resolveOrCreateCompany(String companyName, String ownerName) {
        return companyRepository.findAll().stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(companyName))
                .findFirst()
                .orElseGet(() -> companyRepository.save(
                        Company.builder()
                                .name(companyName)
                                .ownerName(ownerName)
                                .build()
                ));
    }

    private UpsertResult upsertAdmin(AdminSeed seed, Long companyId) {
        User byEmail = userRepository.findByEmailIgnoreCase(seed.email()).orElse(null);
        User byLoginId = userRepository.findByLoginId(seed.loginId()).orElse(null);
        User user = byEmail != null ? byEmail : byLoginId;

        if (user == null) {
            user = User.builder()
                    .loginId(seed.loginId())
                    .email(seed.email())
                    .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                    .active(true)
                    .role(Role.ADMIN)
                    .build();
            user.setCompanyId(companyId);
            userRepository.save(user);
            return UpsertResult.CREATED;
        }

        boolean dirty = false;
        if (user.getEmail() == null || !user.getEmail().equalsIgnoreCase(seed.email())) {
            user.changeEmail(seed.email());
            dirty = true;
        }
        if (!user.isActive()) {
            user.activate();
            dirty = true;
        }
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MASTER) {
            user.changeRole(Role.ADMIN);
            dirty = true;
        }
        if (user.getCompanyId() == null || !user.getCompanyId().equals(companyId)) {
            user.setCompanyId(companyId);
            dirty = true;
        }

        if (dirty) {
            userRepository.save(user);
            return UpsertResult.UPDATED;
        }
        return UpsertResult.UNCHANGED;
    }

    private int seedCustomsBrokers(Long companyId) {
        CustomsBrokerSeed seed = DEFAULT_CUSTOMS_BROKER;

        List<CustomsBroker> brokers = customsBrokerRepository.findAllByCompanyIdOrderByNameAsc(companyId);
        CustomsBroker target = customsBrokerRepository.findByCompanyIdAndBusinessNo(companyId, seed.businessNo())
                .orElseGet(() -> brokers.stream()
                        .filter(b -> b.getName().equalsIgnoreCase(seed.name()))
                        .findFirst()
                        .orElse(null));

        int changed = 0;
        if (target == null) {
            target = CustomsBroker.builder()
                    .name(seed.name())
                    .phone(seed.phone())
                    .businessNo(seed.businessNo())
                    .email(seed.email())
                    .active(true)
                    .build();
            target.setCompanyId(companyId);
            target = customsBrokerRepository.save(target);
            changed++;
        } else {
            target.update(seed.name(), seed.phone(), seed.email());
            target.activate();
            customsBrokerRepository.save(target);
        }

        for (CustomsBroker broker : brokers) {
            if (broker.getId().equals(target.getId())) {
                continue;
            }
            if (broker.isActive()) {
                broker.deactivate();
                customsBrokerRepository.save(broker);
                changed++;
            }
        }

        return changed;
    }

    private record AdminSeed(
            String loginId,
            String email,
            String companyName,
            String ownerName
    ) {}

    private record CustomsBrokerSeed(
            String name,
            String phone,
            String businessNo,
            String email
    ) {}

    private enum UpsertResult {
        CREATED,
        UPDATED,
        UNCHANGED
    }
}
