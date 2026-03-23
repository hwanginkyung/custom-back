package exps.customs;

import exps.customs.domain.brokercase.entity.*;
import exps.customs.domain.brokercase.repository.BrokerCaseRepository;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.domain.login.entity.Company;
import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import exps.customs.domain.login.repository.CompanyRepository;
import exps.customs.domain.login.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
@Profile("!prod")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer implements CommandLineRunner {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final BrokerClientRepository clientRepository;
    private final BrokerCaseRepository caseRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (companyRepository.count() > 0) {
            log.info("[DevData] data already exists, skipping initialization");
            return;
        }

        log.info("[DevData] initializing dev data...");

        // Company
        Company company = Company.builder()
                .companyName("진솔 관세법인")
                .businessNumber("123-45-67890")
                .build();
        companyRepository.save(company);

        // Admin user
        User admin = User.builder()
                .loginId("admin@jinsol.co.kr")
                .email("admin@jinsol.co.kr")
                .passwordHash(passwordEncoder.encode("admin1234"))
                .active(true)
                .role(Role.ADMIN)
                .build();
        admin.setCompanyId(company.getId());
        userRepository.save(admin);

        // Staff user
        User staff = User.builder()
                .loginId("staff@jinsol.co.kr")
                .email("staff@jinsol.co.kr")
                .passwordHash(passwordEncoder.encode("staff1234"))
                .active(true)
                .role(Role.STAFF)
                .build();
        staff.setCompanyId(company.getId());
        userRepository.save(staff);

        // Clients
        BrokerClient client1 = BrokerClient.builder()
                .companyName("(주)한국무역")
                .representativeName("김철수")
                .businessNumber("111-22-33333")
                .phoneNumber("02-1234-5678")
                .email("trade@hankook.co.kr")
                .address("서울시 강남구 역삼동 123-45")
                .active(true)
                .build();
        client1.setCompanyId(company.getId());
        clientRepository.save(client1);

        BrokerClient client2 = BrokerClient.builder()
                .companyName("글로벌 로지스틱스")
                .representativeName("이영희")
                .businessNumber("222-33-44444")
                .phoneNumber("032-987-6543")
                .email("info@globallogistics.kr")
                .address("인천시 중구 신흥동 456-78")
                .active(true)
                .build();
        client2.setCompanyId(company.getId());
        clientRepository.save(client2);

        BrokerClient client3 = BrokerClient.builder()
                .companyName("드림 테크놀로지")
                .representativeName("박민준")
                .businessNumber("333-44-55555")
                .phoneNumber("031-555-1234")
                .email("contact@dreamtech.co.kr")
                .address("경기도 성남시 분당구 판교로 789")
                .active(true)
                .build();
        client3.setCompanyId(company.getId());
        clientRepository.save(client3);

        // Cases
        BrokerCase case1 = BrokerCase.builder()
                .caseNumber("CASE-2026-0001")
                .client(client1)
                .status(CaseStatus.IN_PROGRESS)
                .paymentStatus(PaymentStatus.UNPAID)
                .shippingMethod(ShippingMethod.SEA)
                .blNumber("MSKU1234567")
                .etaDate(LocalDate.of(2026, 3, 25))
                .departurePorts("CNSHA (상해)")
                .arrivalPort("KRINC (인천)")
                .totalAmount(new BigDecimal("50000000"))
                .dutyAmount(new BigDecimal("4000000"))
                .vatAmount(new BigDecimal("5400000"))
                .brokerageFee(new BigDecimal("500000"))
                .memo("전자부품 수입건 - 긴급")
                .assigneeId(staff.getId())
                .build();
        case1.setCompanyId(company.getId());

        CaseCargo cargo1 = CaseCargo.builder()
                .itemName("반도체 칩 (IC)")
                .hsCode("8542.31")
                .quantity(new BigDecimal("10000"))
                .unit("EA")
                .unitPrice(new BigDecimal("5000"))
                .totalPrice(new BigDecimal("50000000"))
                .weight(new BigDecimal("500.000"))
                .originCountry("CN")
                .build();
        case1.addCargo(cargo1);
        caseRepository.save(case1);

        BrokerCase case2 = BrokerCase.builder()
                .caseNumber("CASE-2026-0002")
                .client(client2)
                .status(CaseStatus.CUSTOMS_DECLARED)
                .paymentStatus(PaymentStatus.PAID)
                .shippingMethod(ShippingMethod.AIR)
                .blNumber("180-12345678")
                .etaDate(LocalDate.of(2026, 3, 23))
                .departurePorts("JPNRT (나리타)")
                .arrivalPort("KRICN (인천공항)")
                .totalAmount(new BigDecimal("12000000"))
                .dutyAmount(new BigDecimal("960000"))
                .vatAmount(new BigDecimal("1296000"))
                .brokerageFee(new BigDecimal("300000"))
                .memo("정밀기계 부품")
                .assigneeId(admin.getId())
                .build();
        case2.setCompanyId(company.getId());
        caseRepository.save(case2);

        BrokerCase case3 = BrokerCase.builder()
                .caseNumber("CASE-2026-0003")
                .client(client3)
                .status(CaseStatus.REGISTERED)
                .paymentStatus(PaymentStatus.UNPAID)
                .shippingMethod(ShippingMethod.SEA)
                .etaDate(LocalDate.of(2026, 4, 1))
                .departurePorts("USLA (로스앤젤레스)")
                .arrivalPort("KRPUS (부산)")
                .totalAmount(new BigDecimal("85000000"))
                .memo("화학 원료 수입")
                .assigneeId(staff.getId())
                .build();
        case3.setCompanyId(company.getId());
        caseRepository.save(case3);

        BrokerCase case4 = BrokerCase.builder()
                .caseNumber("CASE-2026-0004")
                .client(client1)
                .status(CaseStatus.COMPLETED)
                .paymentStatus(PaymentStatus.PAID)
                .shippingMethod(ShippingMethod.SEA)
                .blNumber("OOLU7654321")
                .etaDate(LocalDate.of(2026, 3, 10))
                .ataDate(LocalDate.of(2026, 3, 11))
                .customsDate(LocalDate.of(2026, 3, 12))
                .releaseDate(LocalDate.of(2026, 3, 13))
                .departurePorts("DEHAM (함부르크)")
                .arrivalPort("KRINC (인천)")
                .totalAmount(new BigDecimal("30000000"))
                .dutyAmount(new BigDecimal("2400000"))
                .vatAmount(new BigDecimal("3240000"))
                .brokerageFee(new BigDecimal("400000"))
                .memo("완료건 - 자동차 부품")
                .assigneeId(admin.getId())
                .build();
        case4.setCompanyId(company.getId());
        caseRepository.save(case4);

        log.info("[DevData] initialization complete: 1 company, 2 users, 3 clients, 4 cases");
    }
}
