package exps.cariv.global.config;

import exps.cariv.domain.login.entity.Company;
import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import exps.cariv.domain.login.repository.CompanyRepository;
import exps.cariv.domain.login.repository.UserRepository;
import exps.cariv.domain.customs.entity.CustomsBroker;
import exps.cariv.domain.customs.repository.CustomsBrokerRepository;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.entity.ShipperDocument;
import exps.cariv.domain.shipper.entity.ShipperType;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.domain.shipper.repository.ShipperDocumentRepository;
import exps.cariv.domain.vehicle.entity.OwnerType;
import exps.cariv.domain.vehicle.entity.TransmissionType;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;

@Configuration
@Profile("!prod")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer {

    private static final String DEFAULT_LOGIN_ID = "admin";
    private static final String DEFAULT_PASSWORD = "admin1234!";

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final ShipperRepository shipperRepository;
    private final DocumentRepository documentRepository;
    private final ShipperDocumentRepository shipperDocumentRepository;
    private final CustomsBrokerRepository customsBrokerRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public CommandLineRunner seedDevData() {
        return args -> {
            User admin = userRepository.findByLoginId(DEFAULT_LOGIN_ID).orElse(null);

            Company company = (admin != null)
                    ? companyRepository.findById(admin.getCompanyId()).orElseGet(() -> createDefaultCompany())
                    : companyRepository.findAll().stream().findFirst().orElseGet(this::createDefaultCompany);

            if (admin == null) {
                admin = User.builder()
                        .loginId(DEFAULT_LOGIN_ID)
                        .email("admin@cariv.local")
                        .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                        .active(true)
                        .role(Role.ADMIN)
                        .build();
                admin.setCompanyId(company.getId());
                admin = userRepository.save(admin);
            }

            int seededShippers = seedShippers(company.getId());
            int seededVehicles = seedVehicles(company.getId());
            int seededIdCards = seedShipperIdCards(company.getId(), admin.getId());
            int seededBrokers = seedCustomsBrokers(company.getId());

            log.info("[DevDataInitializer] seeded default admin loginId={} companyId={}",
                    DEFAULT_LOGIN_ID, company.getId());
            log.info("[DevDataInitializer] seeded demo shippers count={} companyId={}",
                    seededShippers, company.getId());
            log.info("[DevDataInitializer] seeded demo vehicles count={} companyId={}",
                    seededVehicles, company.getId());
            log.info("[DevDataInitializer] seeded shipper id-card docs count={} companyId={}",
                    seededIdCards, company.getId());
            log.info("[DevDataInitializer] seeded customs brokers count={} companyId={}",
                    seededBrokers, company.getId());
        };
    }

    private Company createDefaultCompany() {
        return companyRepository.save(
                Company.builder()
                        .name("Cariv Demo Company")
                        .ownerName("Demo Owner")
                        .build()
        );
    }

    private int seedShippers(Long companyId) {
        List<ShipperSeed> seeds = List.of(
                new ShipperSeed("해피카", "DEALER", ShipperType.CORPORATE_BUSINESS, "010-1234-5678", "123-45-67890", "서울특별시 강남구 테헤란로 100"),
                new ShipperSeed("스마트트레이드", "DEALER", ShipperType.CORPORATE_BUSINESS, "010-9876-5432", "234-56-78901", "인천광역시 연수구 센트럴로 200"),
                new ShipperSeed("베스트모터스", "DEALER", ShipperType.CORPORATE_BUSINESS, "010-5555-1234", "345-67-89012", "부산광역시 해운대구 센텀중앙로 300")
        );

        int created = 0;
        for (ShipperSeed s : seeds) {
            Shipper shipper = shipperRepository.findTopByCompanyIdAndNameOrderByIdDesc(companyId, s.name())
                    .orElse(null);

            if (shipper == null) {
                shipper = Shipper.builder()
                        .name(s.name())
                        .type(s.type())
                        .shipperType(s.shipperType())
                        .phone(s.phone())
                        .businessNumber(s.businessNumber())
                        .address(s.address())
                        .build();
                shipper.setCompanyId(companyId);
                shipperRepository.save(shipper);
                created++;
                continue;
            }

            // 기존 더미 데이터도 출력 필수 항목이 채워지도록 보정
            shipper.update(s.name(), s.type(), s.shipperType(), s.phone(), s.businessNumber(), s.address());
            shipperRepository.save(shipper);
        }
        return created;
    }

    private int seedShipperIdCards(Long companyId, Long uploadedByUserId) {
        int created = 0;
        List<Shipper> shippers = shipperRepository.findAllByCompanyIdAndActiveTrue(companyId);

        for (Shipper shipper : shippers) {
            boolean exists = documentRepository.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                    companyId, DocumentRefType.SHIPPER, shipper.getId(), DocumentType.ID_CARD
            ).isPresent();
            if (exists) {
                continue;
            }

            String s3Key = "dev-placeholder/shipper/" + shipper.getId() + "/id-card.jpg";
            ShipperDocument idCardDoc = ShipperDocument.createNew(
                    companyId,
                    uploadedByUserId,
                    shipper.getId(),
                    DocumentType.ID_CARD,
                    s3Key,
                    "seed_id_card_" + shipper.getId() + ".jpg",
                    "image/jpeg",
                    1024L
            );
            shipperDocumentRepository.save(idCardDoc);
            created++;
        }

        return created;
    }

    private int seedVehicles(Long companyId) {
        List<VehicleSeed> seeds = List.of(
                new VehicleSeed(
                        "KNAG6412BNA193171", "175로3480", VehicleStage.BEFORE_DEREGISTRATION,
                        "K5", 2022, "중형 승용", "휘발유", "오토", 1598,
                        "해피카", OwnerType.DEALER_CORPORATE, "홍길동", "900101-1234567",
                        29388092L, LocalDate.of(2025, 11, 21)
                ),
                new VehicleSeed(
                        "KNAG6412BNA193172", "234가5678", VehicleStage.BEFORE_REPORT,
                        "AVANTE", 2021, "준중형 승용", "휘발유", "오토", 1591,
                        "스마트트레이드", OwnerType.DEALER_CORPORATE, "김영희", "880305-2234567",
                        17800000L, LocalDate.of(2025, 10, 5)
                ),
                new VehicleSeed(
                        "KNAG6412BNA193173", "321나4321", VehicleStage.BEFORE_CERTIFICATE,
                        "SORENTO", 2020, "SUV", "디젤", "오토", 2151,
                        "베스트모터스", OwnerType.DEALER_CORPORATE, "박민수", "870722-1234567",
                        25200000L, LocalDate.of(2025, 9, 10)
                ),
                new VehicleSeed(
                        "KNAG6412BNA193174", "876다1234", VehicleStage.COMPLETED,
                        "GRANDEUR", 2019, "대형 승용", "휘발유", "오토", 2497,
                        "해피카", OwnerType.DEALER_CORPORATE, "최수진", "860914-2234567",
                        21400000L, LocalDate.of(2025, 8, 2)
                ),
                new VehicleSeed(
                        "KNAG6412BNA193175", "111라2222", VehicleStage.BEFORE_REPORT,
                        "MORNING", 2018, "경형 승용", "휘발유", "오토", 998,
                        "스마트트레이드", OwnerType.DEALER_CORPORATE, "이현우", "920210-1234567",
                        6800000L, LocalDate.of(2025, 7, 15)
                ),
                new VehicleSeed(
                        "KMJYB371BNU017783", "459거7783", VehicleStage.BEFORE_DEREGISTRATION,
                        "PORTER2", 2021, "소형 화물", "디젤", "수동", 2497,
                        "해피카", OwnerType.DEALER_CORPORATE, "정다은", "910412-2234567",
                        18900000L, LocalDate.of(2025, 12, 20)
                )
        );

        int created = 0;
        for (VehicleSeed s : seeds) {
            Long shipperId = shipperRepository.findTopByCompanyIdAndNameOrderByIdDesc(companyId, s.shipperName())
                    .map(Shipper::getId)
                    .orElse(null);

            Vehicle existing = vehicleRepository
                    .findTopByCompanyIdAndVinAndDeletedFalseOrderByIdDesc(companyId, s.vin())
                    .orElse(null);
            if (existing != null) {
                existing.applyFullUpdate(
                        null,
                        s.shipperName(),
                        shipperId,
                        s.ownerType(),
                        s.purchasePrice(),
                        s.purchaseDate(),
                        null,
                        null
                );
                vehicleRepository.save(existing);
                continue;
            }

            Vehicle v = Vehicle.builder()
                    .vin(s.vin())
                    .vehicleNo(s.vehicleNo())
                    .stage(s.stage())
                    .modelName(s.modelName())
                    .modelYear(s.modelYear())
                    .carType(s.carType())
                    .fuelType(s.fuelType())
                    .displacement(s.displacement())
                    .ownerName(s.ownerName())
                    .ownerId(s.ownerId())
                    .shipperName(s.shipperName())
                    .shipperId(shipperId)
                    .ownerType(s.ownerType())
                    .transmission(parseTransmission(s.transmission()))
                    .purchasePrice(s.purchasePrice())
                    .purchaseDate(s.purchaseDate())
                    .build();
            v.setCompanyId(companyId);
            vehicleRepository.save(v);
            created++;
        }
        return created;
    }

    private int seedCustomsBrokers(Long companyId) {
        List<CustomsBrokerSeed> seeds = List.of(
                new CustomsBrokerSeed("서울관세법인", "02-555-0101", "110-81-10001", "seoul-customs@demo.local"),
                new CustomsBrokerSeed("한빛관세사무소", "032-777-0202", "122-86-20002", "hanbit-customs@demo.local"),
                new CustomsBrokerSeed("태평양관세법인", "051-999-0303", "214-81-30003", "pacific-customs@demo.local")
        );

        List<CustomsBroker> existing = customsBrokerRepository.findAllByCompanyIdAndActiveTrueOrderByNameAsc(companyId);
        int created = 0;

        for (CustomsBrokerSeed s : seeds) {
            boolean alreadyExists = existing.stream()
                    .anyMatch(b -> b.getName().equalsIgnoreCase(s.name()));
            if (alreadyExists) continue;

            CustomsBroker broker = CustomsBroker.builder()
                    .name(s.name())
                    .phone(s.phone())
                    .businessNo(s.businessNo())
                    .email(s.email())
                    .active(true)
                    .build();
            broker.setCompanyId(companyId);
            customsBrokerRepository.save(broker);
            created++;
        }

        return created;
    }

    private record ShipperSeed(
            String name,
            String type,
            ShipperType shipperType,
            String phone,
            String businessNumber,
            String address
    ) {}

    private record VehicleSeed(
            String vin,
            String vehicleNo,
            VehicleStage stage,
            String modelName,
            Integer modelYear,
            String carType,
            String fuelType,
            String transmission,
            Integer displacement,
            String shipperName,
            OwnerType ownerType,
            String ownerName,
            String ownerId,
            Long purchasePrice,
            LocalDate purchaseDate
    ) {}

    private record CustomsBrokerSeed(
            String name,
            String phone,
            String businessNo,
            String email
    ) {}

    private TransmissionType parseTransmission(String raw) {
        try {
            return TransmissionType.from(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
