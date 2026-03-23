# CARIV 백엔드 코드 완전분석 학습 가이드

> **대상**: 0~1년차 백엔드 개발자
> **프로젝트**: CARIV — 중고차 수출/통관 관리 시스템
> **기술 스택**: Spring Boot 3.5 / Java 17 / JPA / Redis / JWT / AWS S3 / Docker

---

## 목차

1. [프로젝트 전체 구조 이해하기](#1-프로젝트-전체-구조-이해하기)
2. [Level 1 — 기초 중의 기초](#2-level-1--기초-중의-기초)
3. [Level 2 — Spring Boot 핵심 패턴](#3-level-2--spring-boot-핵심-패턴)
4. [Level 3 — 데이터베이스와 JPA](#4-level-3--데이터베이스와-jpa)
5. [Level 4 — 인증/인가 (JWT + Spring Security)](#5-level-4--인증인가-jwt--spring-security)
6. [Level 5 — Redis 활용](#6-level-5--redis-활용)
7. [Level 6 — 파일 업로드와 AWS S3](#7-level-6--파일-업로드와-aws-s3)
8. [Level 7 — 멀티테넌시 아키텍처](#8-level-7--멀티테넌시-아키텍처)
9. [Level 8 — 비동기 처리와 OCR 작업 큐](#9-level-8--비동기-처리와-ocr-작업-큐)
10. [Level 9 — SSE 실시간 알림](#10-level-9--sse-실시간-알림)
11. [Level 10 — 운영 수준 기법들](#11-level-10--운영-수준-기법들)
12. [기술 전체 요약 맵](#12-기술-전체-요약-맵)

---

## 1. 프로젝트 전체 구조 이해하기

### 이 프로젝트가 뭘 하는 건가요?

CARIV는 **중고차 수출 업무를 관리하는 B2B SaaS**입니다. 여러 회사(테넌트)가 같은 시스템을 쓰면서 각자의 데이터만 볼 수 있어야 합니다.

핵심 업무 흐름:
1. 차량 등록 → 2. 서류 업로드 (차량등록증, 경매서류, 매매계약서 등) → 3. OCR로 자동 데이터 추출 → 4. 말소/수출 신고 → 5. 통관 의뢰

### 폴더 구조

```
src/main/java/exps/cariv/
├── CarivApplication.java          ← 앱 시작점
├── global/                        ← 전역 설정, 공통 코드
│   ├── common/                    ← BaseEntity, TenantEntity
│   ├── config/                    ← Security, Redis, Swagger 등 설정
│   ├── exception/                 ← 에러 처리
│   ├── jwt/                       ← JWT 토큰 발급/검증
│   ├── security/                  ← Spring Security 커스텀
│   ├── aws/                       ← S3 업로드/다운로드
│   ├── tenant/                    ← 멀티테넌시 핵심 로직
│   ├── logging/                   ← MDC 로깅 필터
│   └── parser/                    ← 문서 파싱 유틸리티
└── domain/                        ← 비즈니스 도메인별 분리
    ├── login/                     ← 회원가입, 로그인, 사용자 관리
    ├── vehicle/                   ← 차량 CRUD
    ├── auction/                   ← 경매서류
    ├── contract/                  ← 매매계약서
    ├── registration/              ← 차량등록증
    ├── malso/                     ← 말소 처리
    ├── export/                    ← 수출 신고
    ├── customs/                   ← 통관 의뢰
    ├── shipper/                   ← 화주(수출자) 관리
    ├── ocr/                       ← OCR 작업 큐 처리
    ├── notification/              ← 실시간 알림 (SSE)
    ├── upstage/                   ← 외부 OCR API 연동
    └── document/                  ← 문서 공통 엔티티
```

**핵심 패턴**: 각 도메인은 `controller → service → repository → entity` 구조를 따릅니다. 이걸 **Layered Architecture(계층형 아키텍처)** 라고 합니다.

---

## 2. Level 1 — 기초 중의 기초

### 2-1. Spring Boot 애플리케이션 시작점

```java
// CarivApplication.java
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableJpaAuditing
public class CarivApplication {
    public static void main(String[] args) {
        SpringApplication.run(CarivApplication.class, args);
    }
}
```

**한 줄씩 뜯어보기:**

- `@SpringBootApplication`: "이 클래스가 Spring Boot 앱의 시작점이야"라고 선언. 내부적으로 컴포넌트 스캔, 자동 설정 등을 켜줍니다.
- `@EnableScheduling`: `@Scheduled` 어노테이션을 쓸 수 있게 해줍니다. 이 프로젝트에서는 OCR 큐 모니터링, SSE heartbeat, 알림 정리 등에 사용됩니다.
- `@EnableAsync`: `@Async` 어노테이션을 쓸 수 있게 해줍니다. SSE 푸시를 비동기로 처리할 때 사용합니다.
- `@EnableJpaAuditing`: `@CreatedDate`, `@LastModifiedDate`가 자동으로 동작하게 해줍니다.

### 2-2. build.gradle 이해하기

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.10'
    id 'io.spring.dependency-management' version '1.1.7'
}
```

**build.gradle이 뭔가요?** Gradle은 빌드 도구입니다. "이 프로젝트에 어떤 라이브러리가 필요한지, 어떤 버전의 Java를 쓸 건지"를 정의하는 파일이에요.

**이 프로젝트에서 쓰는 주요 라이브러리들:**

| 라이브러리 | 용도 | 왜 필요한가 |
|---|---|---|
| `spring-boot-starter-web` | REST API 서버 | 웹 요청을 받고 JSON 응답하려고 |
| `spring-boot-starter-data-jpa` | DB 접근 (ORM) | SQL 안 짜고 Java 객체로 DB 다루려고 |
| `spring-boot-starter-security` | 인증/인가 | 로그인, 권한 체크 |
| `spring-boot-starter-data-redis` | Redis 연동 | 캐싱, 세션, 작업 큐 |
| `spring-boot-starter-webflux` | WebClient | 외부 API 호출 (Upstage OCR) |
| `spring-boot-starter-mail` | 이메일 발송 | 비밀번호 초기화 등 |
| `jjwt-api/impl/jackson` | JWT 토큰 | 로그인 토큰 만들기 |
| `springdoc-openapi` | Swagger | API 문서 자동 생성 |
| `software.amazon.awssdk:s3` | AWS S3 | 파일 업로드/다운로드 |
| `apache-poi` | Excel 파일 | 인보이스 Excel 생성 |
| `pdfbox` | PDF 처리 | PDF 생성/읽기 |
| `jsoup` | HTML 파싱 | OCR 결과에서 테이블 추출 |
| `playwright` | 브라우저 자동화 | Excel→PDF 변환 등 |
| `lombok` | 보일러플레이트 제거 | getter/setter/생성자 자동 생성 |

### 2-3. Lombok이 뭔가요?

이 프로젝트 거의 모든 파일에서 보이는 `@Getter`, `@RequiredArgsConstructor`, `@Slf4j` 등은 전부 Lombok입니다.

```java
// Lombok 없이 쓰면 이렇게 길어집니다
public class MyService {
    private static final Logger log = LoggerFactory.getLogger(MyService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public MyService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }
}

// Lombok 쓰면 이렇게 짧아집니다
@Slf4j                        // log 변수 자동 생성
@RequiredArgsConstructor      // final 필드를 파라미터로 받는 생성자 자동 생성
public class MyService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
}
```

주요 Lombok 어노테이션:

| 어노테이션 | 하는 일 |
|---|---|
| `@Getter` | 모든 필드에 getter 메서드 자동 생성 |
| `@Setter` | 모든 필드에 setter 메서드 자동 생성 |
| `@RequiredArgsConstructor` | final 필드만 받는 생성자 자동 생성 |
| `@NoArgsConstructor` | 파라미터 없는 기본 생성자 자동 생성 |
| `@Builder` | Builder 패턴 자동 생성 |
| `@Slf4j` | `log` 변수 자동 생성 (로깅용) |

### 2-4. Java record란?

이 프로젝트의 DTO들이 대부분 `record`로 작성되어 있습니다.

```java
// 이 프로젝트의 실제 코드
public record LoginRequest(String loginId, String password) {}
public record TokenResponse(String accessToken) {}
public record SignupRequest(String companyId, String loginId, String password) {}
```

**record는 뭔가요?** Java 16에서 도입된 기능으로, **불변(immutable) 데이터 클래스**를 한 줄로 만들 수 있습니다. 위 `LoginRequest`는 아래와 같은 코드를 자동으로 생성합니다:

```java
// record가 자동으로 만들어주는 것들:
// - private final 필드 (loginId, password)
// - 생성자
// - getter (loginId(), password())
// - equals(), hashCode(), toString()
```

**왜 DTO에 record를 쓰나요?** DTO(Data Transfer Object)는 데이터를 담아서 전달하는 용도입니다. 한번 만들면 값을 바꿀 필요가 없으니 불변인 record가 딱 맞습니다.

### 2-5. application.yaml 이해하기

```yaml
server:
  port: 8080                    # 서버가 8080번 포트에서 실행

spring:
  datasource:
    url: jdbc:h2:mem:cafe       # H2 인메모리 DB 사용 (개발용)
  jpa:
    hibernate:
      ddl-auto: create          # 앱 시작할 때 테이블 자동 생성
    show-sql: true              # 실행되는 SQL 로그에 출력

jwt:
  secret: ${JWT_SECRET:기본값}   # 환경변수가 없으면 기본값 사용
  access-token-expire-ms: 1800000   # 30분
  refresh-token-expire-ms: 1209600000  # 14일
```

**`${JWT_SECRET:기본값}` 문법**: 환경변수 `JWT_SECRET`이 있으면 그 값을 쓰고, 없으면 `:` 뒤의 기본값을 씁니다. 이렇게 하면 개발할 땐 기본값으로, 운영 서버에선 환경변수로 비밀값을 주입할 수 있습니다.

---

## 3. Level 2 — Spring Boot 핵심 패턴

### 3-1. Controller → Service → Repository 패턴

이 프로젝트의 모든 도메인이 이 패턴을 따릅니다. 로그인을 예로 들어볼게요.

**① Controller (요청을 받는 문지기)**

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String clientIp = resolveClientIp(httpRequest);
        TokenIssueResult result = authService.login(request, clientIp);

        // Refresh Token을 HttpOnly 쿠키로 내려보냄
        addRefreshCookie(httpResponse, result.refreshToken());

        return ResponseEntity.ok(new TokenResponse(result.accessToken()));
    }
}
```

**역할 분담 설명:**
- `@RestController`: "이 클래스는 REST API를 처리한다"
- `@RequestMapping("/api/auth")`: 이 컨트롤러의 모든 URL은 `/api/auth`로 시작
- `@PostMapping("/login")`: POST `/api/auth/login` 요청을 처리
- `@RequestBody`: JSON 요청 본문을 Java 객체로 변환
- `ResponseEntity`: HTTP 응답 (상태코드 + 본문)

**② Service (비즈니스 로직 담당)**

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginSecurityService loginSecurityService;

    @Transactional
    public TokenIssueResult login(LoginRequest request, String clientIp) {
        // 1. 보안 체크 (계정 잠금, IP 제한)
        loginSecurityService.assertAllowed(request.loginId(), clientIp);

        // 2. 사용자 조회
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> { /* 실패 처리 */ });

        // 3. 비밀번호 확인
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginSecurityService.onFailure(request.loginId(), clientIp);
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 4. 토큰 발급
        loginSecurityService.onSuccess(request.loginId());
        String accessToken = jwtTokenProvider.createAccessToken(user);
        String refreshToken = jwtTokenProvider.createRefreshToken(user);

        return new TokenIssueResult(accessToken, refreshToken);
    }
}
```

**왜 Controller에서 바로 DB를 안 건드리나요?** 관심사의 분리(Separation of Concerns) 때문입니다:
- Controller: HTTP 요청/응답만 처리
- Service: 비즈니스 규칙 처리
- Repository: DB 접근만 담당

이렇게 나누면 테스트하기 쉽고, 나중에 웹 API가 아니라 다른 방식(메시지 큐 등)으로 같은 로직을 호출할 수도 있습니다.

**③ Repository (DB 접근 담당)**

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLoginId(String loginId);
    List<User> findAllByCompanyIdAndRoleNot(Long companyId, Role role);
}
```

Spring Data JPA의 마법입니다. **인터페이스만 선언하면 구현체를 Spring이 자동으로 만들어줍니다!**

- `findByLoginId(String loginId)` → `SELECT * FROM users WHERE login_id = ?`
- `findAllByCompanyIdAndRoleNot(Long companyId, Role role)` → `SELECT * FROM users WHERE company_id = ? AND role != ?`

메서드 이름의 규칙: `findBy` + 필드명 + 조건(`And`, `Or`, `Not`, `In`, `Like` 등)

### 3-2. DTO 패턴 (Request/Response 분리)

이 프로젝트는 Request와 Response를 별도 DTO로 분리합니다.

```
domain/login/dto/
├── LoginRequest.java         ← 클라이언트 → 서버
├── SignupRequest.java        ← 클라이언트 → 서버
├── TokenResponse.java        ← 서버 → 클라이언트
├── TokenIssueResult.java     ← 내부 전달용
└── MyPageResponse.java       ← 서버 → 클라이언트
```

**왜 Entity를 직접 반환하면 안 되나요?**
1. Entity에는 `passwordHash` 같은 민감 정보가 있음 → 그대로 내보내면 보안 문제
2. Entity 구조가 바뀌면 API 응답도 바뀜 → 프론트엔드가 깨짐
3. API마다 필요한 필드가 다름 → 목록 조회와 상세 조회의 응답이 다를 수 있음

### 3-3. CQRS 유사 패턴 (Command와 Query 분리)

이 프로젝트의 Service는 **Command**와 **Query**로 나뉘어 있습니다:

```
domain/vehicle/service/
├── VehicleCommandService.java    ← 생성, 수정, 삭제 (쓰기)
├── VehicleQueryService.java      ← 목록 조회, 상세 조회 (읽기)
└── VehicleDocumentService.java   ← 문서 관련 처리
```

**왜 나누나요?**
- 읽기 작업은 보통 `@Transactional(readOnly = true)`를 써서 성능 최적화
- 쓰기 작업은 `@Transactional`로 데이터 일관성 보장
- 하나의 서비스가 너무 커지는 것을 방지 (단일 책임 원칙)

---

## 4. Level 3 — 데이터베이스와 JPA

### 4-1. Entity 기초 — BaseEntity

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseEntity implements Serializable {

    @CreatedDate
    @Column(updatable = false, nullable = false)
    protected Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
```

**한 줄씩 뜯어보기:**

- `@MappedSuperclass`: "이 클래스의 필드를 자식 클래스의 테이블에 포함시켜줘." 별도 테이블은 안 만듭니다.
- `@EntityListeners(AuditingEntityListener.class)`: `@CreatedDate`, `@LastModifiedDate`가 자동으로 동작하게 해줍니다.
- `@CreatedDate`: 엔티티가 처음 저장될 때 현재 시간이 자동으로 들어갑니다.
- `@LastModifiedDate`: 엔티티가 수정될 때마다 현재 시간이 자동으로 갱신됩니다.
- `@Column(updatable = false)`: 한번 저장되면 수정 불가 (생성일은 바뀌면 안 되니까)
- `Instant`: Java 8 날짜/시간 API. `LocalDateTime`과 달리 **타임존 없는 UTC 기준** 시점을 나타냅니다.

**왜 `abstract class`인가요?** `BaseEntity`는 직접 인스턴스를 만들 용도가 아니라, 다른 Entity들이 상속받아 사용하는 용도입니다.

### 4-2. Entity 예시 — Vehicle

```java
@Entity
@Table(name = "vehicles")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vehicle extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 17)
    private String vin;                    // 차대번호

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStage stage;            // 진행 단계

    private boolean deleted = false;       // 소프트 삭제

    // ... 수많은 필드들
}
```

**핵심 개념들:**

- `@Entity`: "이 클래스는 DB 테이블과 매핑된다"
- `@Table(name = "vehicles")`: 테이블 이름 지정
- `@Id` + `@GeneratedValue(strategy = GenerationType.IDENTITY)`: 자동 증가하는 PK (MySQL의 AUTO_INCREMENT)
- `@Enumerated(EnumType.STRING)`: Enum을 DB에 문자열로 저장 (`"BEFORE_DEREGISTRATION"`)
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)`: JPA가 내부적으로 쓰는 기본 생성자인데, 외부에서 직접 호출하지 못하게 `PROTECTED`로 설정
- `deleted = false`: **소프트 삭제(Soft Delete)** 패턴. 실제로 DB에서 삭제하지 않고 `deleted` 플래그만 바꿉니다.

**Enum으로 상태 관리:**

```java
public enum VehicleStage {
    BEFORE_DEREGISTRATION,   // 말소 전
    BEFORE_REPORT,           // 수출신고 전
    BEFORE_CERTIFICATE,      // 수출이행확인 전
    COMPLETED                // 완료
}
```

이렇게 하면 코드에서 `if (stage == 1)` 같은 매직넘버 대신 `if (stage == VehicleStage.BEFORE_REPORT)` 처럼 읽기 좋은 코드를 쓸 수 있습니다.

### 4-3. JPA Specification — 동적 쿼리

이 프로젝트에서 가장 인상적인 JPA 활용 중 하나입니다.

```java
public final class VehicleSpecification {

    public static Specification<Vehicle> companyIs(Long companyId) {
        return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
    }

    public static Specification<Vehicle> keywordLike(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;  // null이면 조건 무시!
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("vehicleNo")), pattern),
                cb.like(cb.lower(root.get("vin")), pattern),
                cb.like(cb.lower(root.get("modelName")), pattern)
        );
    }

    public static Specification<Vehicle> stageIn(List<VehicleStage> stages) {
        if (stages == null || stages.isEmpty()) return null;
        return (root, query, cb) -> root.get("stage").in(stages);
    }
}
```

**왜 이게 필요한가요?** 차량 목록 조회를 생각해봅시다:
- "말소 전 단계인 차량만 보여줘" → stage 필터
- "현대 소나타 찾아줘" → keyword 검색
- "2024년 이후에 등록된 것만" → 날짜 필터
- "화주가 김철수인 것만" → shipper 필터

이 조건들이 **조합으로** 들어옵니다. if문으로 SQL을 직접 조합하면 코드가 끔찍해집니다. Specification은 조건을 레고 블록처럼 조립할 수 있게 해줍니다:

```java
// 사용하는 쪽
Specification<Vehicle> spec = Specification.where(companyIs(companyId))
        .and(notDeleted())
        .and(stageIn(stages))       // stages가 null이면 자동으로 무시됨!
        .and(keywordLike(keyword))  // keyword가 null이면 무시!
        .and(createdAfter(startDate));

Page<Vehicle> result = vehicleRepository.findAll(spec, pageable);
```

**서브쿼리까지 쓰는 고급 활용:**

```java
/** 특정 문서 타입이 연결된 차량인지 여부(exists 서브쿼리) */
public static Specification<Vehicle> hasVehicleDocument(DocumentType type) {
    return (root, query, cb) -> {
        Subquery<Long> sq = query.subquery(Long.class);
        var doc = sq.from(Document.class);
        sq.select(doc.get("id")).where(
                cb.equal(doc.get("companyId"), root.get("companyId")),
                cb.equal(doc.get("refId"), root.get("id")),
                cb.equal(doc.get("type"), type)
        );
        return cb.exists(sq);
    };
}
```

이건 SQL로 쓰면 이런 겁니다:
```sql
SELECT v.* FROM vehicles v
WHERE EXISTS (
    SELECT d.id FROM documents d
    WHERE d.company_id = v.company_id
      AND d.ref_id = v.id
      AND d.type = 'REGISTRATION'
)
```

---

## 5. Level 4 — 인증/인가 (JWT + Spring Security)

이 프로젝트의 인증 시스템은 **JWT(JSON Web Token) 기반 Stateless 인증**입니다.

### 5-1. JWT가 뭔가요?

전통적인 세션 방식: 서버가 로그인 상태를 메모리에 저장 → 서버 여러 대일 때 문제

JWT 방식: 서버가 "이 사용자는 인증된 사람이야"라는 정보를 **토큰**이라는 문자열에 담아서 클라이언트에게 줍니다. 이후 클라이언트는 매 요청마다 이 토큰을 보내고, 서버는 토큰을 검증만 합니다.

```
[로그인 과정]
클라이언트 → POST /api/auth/login {loginId, password}
서버 → 비밀번호 확인 → JWT 토큰 생성 → {accessToken: "eyJhbG..."}
클라이언트 ← accessToken 저장

[이후 요청]
클라이언트 → GET /api/vehicles (Header: Authorization: Bearer eyJhbG...)
서버 → 토큰 검증 → 요청 처리 → 응답
```

### 5-2. JWT 토큰 생성 — JwtTokenProvider

```java
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secretKey;

    private Key cachedSigningKey;  // 서명 키를 한 번만 생성해서 캐시

    @PostConstruct
    private void initSigningKey() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {  // HS256은 최소 256bit(32바이트) 필요
            throw new IllegalStateException("JWT_SECRET 길이가 부족합니다");
        }
        this.cachedSigningKey = Keys.hmacShaKeyFor(keyBytes);
    }
```

**`@PostConstruct`란?** Bean이 생성되고 의존성 주입이 완료된 직후에 한 번 실행됩니다. 여기서는 앱 시작 시 서명 키를 한 번만 만들어두고 재사용합니다.

**Access Token 생성:**

```java
    public String createAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpireMs);

        return Jwts.builder()
                .setSubject(user.getLoginId())           // 토큰의 주인
                .claim("type", "access")                 // 토큰 종류
                .claim("userId", user.getId())           // 사용자 ID
                .claim("companyId", user.getCompanyId()) // 회사 ID (멀티테넌시!)
                .claim("role", user.getRole().name())    // 권한
                .setIssuedAt(now)                        // 발급 시각
                .setExpiration(expiry)                   // 만료 시각
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)  // 서명
                .compact();                              // 문자열로 변환
    }
```

JWT에 `companyId`를 넣은 것이 핵심입니다 — 이것이 나중에 멀티테넌시의 열쇠가 됩니다.

### 5-3. JWT 필터 — JwtAuthenticationFilter

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/auth/login")
            || path.equals("/api/auth/refresh")
            || path.equals("/api/auth/signup");
        // → 이 경로들은 토큰 없이 접근 가능
    }

    @Override
    protected void doFilterInternal(...) {
        String token = resolveToken(request);  // "Bearer xxx"에서 토큰 추출

        if (token != null) {
            Claims claims = jwtTokenProvider.validateAndGetClaims(token);

            // access 토큰만 허용 (refresh 토큰으로 API 호출 방지)
            if (!jwtTokenProvider.isTokenType(claims, "access")) {
                throw new CustomException(ErrorCode.TOKEN_INVALID);
            }

            // Spring Security에 "이 사용자는 인증됐어" 알려주기
            var authentication = jwtTokenProvider.getAuthentication(claims);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 멀티테넌시: JWT에서 companyId를 꺼내 ThreadLocal에 저장
            Long companyId = ((Number) claims.get("companyId")).longValue();
            TenantContext.setCompanyId(companyId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();  // 반드시 정리! (메모리 누수 방지)
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
```

**Filter란?** HTTP 요청이 Controller에 도달하기 전에 먼저 거치는 관문입니다. 필터 체인 순서:

```
HTTP 요청 → [CORS Filter] → [JWT Filter] → [MDC Filter] → [Security] → Controller
```

**`OncePerRequestFilter`**: 하나의 요청에 대해 딱 한 번만 실행되는 필터. Spring의 내부 포워딩 때문에 같은 요청이 여러 번 필터를 탈 수 있는데, 이걸 방지합니다.

### 5-4. Spring Security 설정 — SecurityConfig

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)          // JWT 쓰니까 CSRF 불필요
                .httpBasic(AbstractHttpConfigurer::disable)      // 기본 인증 끄기
                .formLogin(AbstractHttpConfigurer::disable)      // 폼 로그인 끄기
                .sessionManagement(s -> s.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))       // 세션 안 씀!

                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/auth/login",
                            "/api/auth/refresh",
                            "/api/auth/signup"
                    ).permitAll();                              // 인증 없이 접근 가능

                    auth.requestMatchers("/api/master/**").hasRole("MASTER")    // MASTER만
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "MASTER")
                        .requestMatchers("/api/**").authenticated()             // 나머지는 인증 필요
                        .anyRequest().denyAll();                                // 그 외 전부 차단
                })

                // JWT 필터를 Security 필터 뒤에 추가
                .addFilterAfter(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new MdcFilter(), JwtAuthenticationFilter.class)
                .build();
    }
```

**URL별 권한 관리를 한 곳에서 선언적으로** 합니다. 이게 없으면 모든 Controller 메서드에 `if (user.getRole() != MASTER) throw ...`를 넣어야 합니다.

**보안 헤더 설정도 있습니다:**

```java
.headers(h -> {
    h.frameOptions(f -> f.deny());                // iframe 삽입 차단 (클릭재킹 방지)
    h.contentSecurityPolicy(csp -> csp
            .policyDirectives("default-src 'self'; ..."));  // XSS 방지
    h.httpStrictTransportSecurity(hsts -> hsts
            .maxAgeInSeconds(31536000));           // HTTPS 강제
})
```

### 5-5. CORS 설정

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedOrigins(mergedOrigins);  // 허용할 프론트엔드 주소
    config.setAllowCredentials(true);         // 쿠키 전송 허용
    config.setMaxAge(3600L);                  // preflight 캐시 1시간
}
```

**CORS가 뭔가요?** 브라우저는 보안상 `http://localhost:5173`(프론트엔드)에서 `http://localhost:8080`(백엔드)으로 직접 요청을 보내지 못합니다. CORS 설정은 "이 출처에서 오는 요청은 허용해줘"라고 서버가 브라우저에게 알려주는 겁니다.

### 5-6. Refresh Token과 HttpOnly Cookie

이 프로젝트는 **Access Token + Refresh Token** 이중 토큰 전략을 씁니다:

```
Access Token:  짧은 수명(30분), 요청 헤더로 전송
Refresh Token: 긴 수명(14일), HttpOnly Cookie로 전송
```

**왜 둘로 나누나요?**
- Access Token이 탈취되더라도 30분이면 만료됨
- Refresh Token은 HttpOnly Cookie에 있어서 JavaScript로 접근 불가 (XSS 방지)
- Access Token이 만료되면 Refresh Token으로 새 Access Token을 발급받음

---

## 6. Level 5 — Redis 활용

이 프로젝트는 Redis를 **4가지 용도**로 사용합니다.

### 6-1. 로그인 보안 (Rate Limiting)

```java
@Service
public class LoginSecurityService {

    // Redis 키 구조:
    // auth:login:fail:user:{loginId} → 실패 횟수
    // auth:login:lock:user:{loginId} → 계정 잠금 상태
    // auth:login:fail:ip:{ip}       → IP별 실패 횟수

    public void assertAllowed(String loginId, String clientIp) {
        // 1. 계정 잠금 확인
        Boolean locked = redis.hasKey(lockKey(normalizedLoginId));
        if (Boolean.TRUE.equals(locked)) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS, "잠시 후 다시 시도해주세요.");
        }

        // 2. IP별 시도 횟수 확인
        Long ipAttempts = parseLong(redis.opsForValue().get(ipFailKey(normalizedIp)));
        if (ipAttempts != null && ipAttempts >= maxAttemptsPerIp) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    public void onFailure(String loginId, String clientIp) {
        // 실패 카운트 증가 (TTL 자동 설정)
        long userFailures = incrementWithTtl(userFailKey(normalizedLoginId), Duration.ofMinutes(10));
        incrementWithTtl(ipFailKey(normalizedIp), Duration.ofMinutes(10));

        // 5번 실패하면 15분 잠금
        if (userFailures >= maxFailuresPerUser) {
            redis.opsForValue().set(lockKey(normalizedLoginId), "1", Duration.ofMinutes(15));
        }
    }

    private long incrementWithTtl(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);  // 원자적 증가
        if (count <= 1L) {
            redis.expire(key, ttl);  // 첫 번째 증가일 때만 TTL 설정
        }
        return count;
    }
}
```

**왜 Redis를 쓰나요?** 로그인 실패 횟수 같은 건 영구 저장할 필요 없고, 빠르게 읽고 써야 합니다. Redis는 메모리 기반이라 초당 수십만 건 처리 가능하고, TTL(자동 만료)을 지원해서 "10분 후 자동 삭제" 같은 게 쉽습니다.

**`incrementWithTtl` 패턴**: 이 프로젝트에서 여러 번 나오는 핵심 패턴입니다.
1. `increment(key)`: 키 값을 1 증가 (키가 없으면 자동 생성)
2. 값이 1이면 (방금 새로 생성됨) TTL을 설정
3. 이렇게 하면 "10분 윈도우 내 실패 횟수"를 자연스럽게 추적할 수 있습니다.

**`fail-open` 전략**: Redis가 다운되면 어떻게 할까요?

```java
} catch (Exception e) {
    // Redis 장애 시 전체 로그인 장애를 막기 위해 fail-open
    log.error("[LoginSecurity] check failed (fail-open)");
}
```

Redis가 죽어도 로그인 자체는 되게 합니다. 보안 체크를 못하는 게 아쉽지만, 서비스 전체가 죽는 것보다 낫습니다.

### 6-2. Refresh Token 관리

```java
@Service
public class RefreshTokenService {
    // Redis에 해시된 Refresh Token 저장
    // 키: rt:{userId}:{hashedToken}
    // TTL: 14일
    // → 사용자별 모든 Refresh Token 폐기(로그아웃) 가능
}
```

### 6-3. 사용자 정보 캐싱

```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Cacheable(cacheNames = RedisConfig.USER_DETAILS_CACHE, key = "#username")
    public UserDetails loadUserByUsername(String username) {
        // DB에서 조회 → Redis에 5분간 캐시
    }

    @CacheEvict(cacheNames = RedisConfig.USER_DETAILS_CACHE, key = "#loginId")
    public void evictUserDetailsCache(String loginId) {
        // 캐시 무효화 (비밀번호 변경 시 등)
    }
}
```

**`@Cacheable`**: 같은 파라미터로 호출하면 실제 메서드를 실행하지 않고 캐시된 결과를 반환합니다. 매 API 요청마다 DB를 조회하는 대신 Redis에서 가져옵니다.

### 6-4. OCR 작업 큐 (가장 고급 활용)

```java
// Redis List를 메시지 큐처럼 사용
// LPUSH로 작업 추가, BRPOP으로 작업 꺼내기
redisTemplate.opsForList().leftPush("ocr:jobs:queue", jobId);  // 생산자
redisTemplate.opsForList().rightPop("ocr:jobs:queue", Duration.ofSeconds(2));  // 소비자
```

이건 Level 8에서 자세히 다룹니다.

---

## 7. Level 6 — 파일 업로드와 AWS S3

### 7-1. S3 업로드 기본

```java
@Service
public class S3Upload {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "jpg", "jpeg", "png", "xlsx", "xls", "docx"
    );

    public UploadResult uploadRawDocument(Long companyId, Long documentId, MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        validateFileExtension(originalFilename);          // 확장자 검증
        String safeName = safe(originalFilename);          // 특수문자 제거

        // S3 키 구조: raw-documents/{회사ID}/{문서ID}/{UUID}-{파일명}
        String key = "raw-documents/"
                + companyId + "/"
                + documentId + "/"
                + UUID.randomUUID() + "-" + safeName;

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

        s3Client.putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        return new UploadResult(key, originalFilename, ct, file.getSize());
    }
}
```

**핵심 포인트들:**

1. **파일 확장자 검증**: 허용된 확장자만 업로드 가능 → 보안
2. **UUID로 파일명 중복 방지**: 같은 이름의 파일을 여러 번 올려도 겹치지 않음
3. **회사별 폴더 분리**: `raw-documents/{companyId}/...` → 멀티테넌시
4. **record로 결과 반환**: `record UploadResult(String s3Key, String originalFilename, String contentType, long sizeBytes)`

### 7-2. MultipartFile이 뭔가요?

프론트엔드에서 파일을 `<input type="file">`로 업로드하면, Spring이 이걸 `MultipartFile` 객체로 변환해줍니다.

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB        # 파일 하나당 최대 20MB
      max-request-size: 20MB     # 요청 전체 최대 20MB
```

---

## 8. Level 7 — 멀티테넌시 아키텍처

**이 프로젝트에서 가장 아키텍처적으로 깊은 부분입니다.**

### 8-1. 멀티테넌시란?

하나의 애플리케이션(코드, DB)을 여러 회사가 공유하면서, **각 회사는 자기 데이터만 볼 수 있어야** 합니다.

```
[A 회사 사용자] → 서버 → DB에서 company_id=1인 데이터만 조회
[B 회사 사용자] → 서버 → DB에서 company_id=2인 데이터만 조회
```

이 프로젝트는 **3단계 방어**로 멀티테넌시를 구현합니다.

### 8-2. 1단계 — ThreadLocal로 현재 테넌트 추적

```java
public class TenantContext {
    private static final ThreadLocal<Long> COMPANY_ID = new ThreadLocal<>();

    public static void setCompanyId(Long companyId) { COMPANY_ID.set(companyId); }
    public static Long getCompanyId() { return COMPANY_ID.get(); }
    public static void clear() { COMPANY_ID.remove(); }
}
```

**ThreadLocal이 뭔가요?** 각 HTTP 요청은 별도의 스레드에서 처리됩니다. `ThreadLocal`은 **스레드마다 독립적인 변수 저장소**입니다.

```
[스레드1 - A회사 요청] → TenantContext.companyId = 1
[스레드2 - B회사 요청] → TenantContext.companyId = 2
// 서로 간섭하지 않음!
```

**JWT Filter에서 설정:**

```java
// JwtAuthenticationFilter에서
Long companyId = ((Number) claims.get("companyId")).longValue();
TenantContext.setCompanyId(companyId);  // ← 여기서 세팅

// 요청 처리 끝나면
TenantContext.clear();  // ← 반드시 정리! (스레드 재사용 때 이전 값이 남아있을 수 있음)
```

### 8-3. 2단계 — Entity 저장 시 자동 테넌트 할당 (JPA EntityListener)

```java
@MappedSuperclass
@EntityListeners(TenantEntityListener.class)
public abstract class TenantEntity extends BaseEntity {
    @Column(name = "company_id", nullable = false)
    protected Long companyId;
}
```

```java
public class TenantEntityListener {

    @PrePersist  // JPA가 INSERT 하기 직전에 호출
    public void prePersist(TenantEntity e) {
        if (e.getCompanyId() == null) {
            Long cid = TenantContext.getCompanyId();
            if (cid == null) throw new CustomException(ErrorCode.UNAUTHORIZED);
            e.setCompanyId(cid);  // 자동으로 현재 회사 ID 할당
        }
    }

    @PreUpdate  // JPA가 UPDATE 하기 직전에 호출
    public void preUpdate(TenantEntity e) {
        Long cid = TenantContext.getCompanyId();
        // 다른 회사 데이터를 수정하려는 시도 차단
        if (cid != null && e.getCompanyId() != null && !e.getCompanyId().equals(cid)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }
    }
}
```

**이게 왜 좋은가요?** 개발자가 서비스 코드에서 `vehicle.setCompanyId(...)` 를 깜빡 잊어도, JPA가 자동으로 넣어줍니다. 그리고 A회사 사용자가 B회사 데이터를 수정하려 하면 자동으로 차단됩니다.

### 8-4. 3단계 — 조회 시 자동 필터링 (Hibernate Filter + AOP)

이 부분이 가장 고급입니다. **AOP(Aspect-Oriented Programming)** 를 사용합니다.

```java
// 1. 어노테이션 정의
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantFiltered { }

// 2. 어노테이션이 붙은 메서드 실행 시 자동으로 Hibernate Filter 활성화
@Aspect
@Component
public class TenantFilterAspect {

    private final EntityManager em;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    @Around("@annotation(TenantFiltered) || @within(TenantFiltered)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Long companyId = TenantContext.getCompanyId();
        if (companyId == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "테넌트 컨텍스트가 설정되지 않았습니다");
        }

        Session session = em.unwrap(Session.class);

        int depth = DEPTH.get();
        boolean enabledHere = false;

        // 중첩 호출 시 최초 한 번만 Filter 활성화
        if (depth == 0) {
            Filter filter = session.enableFilter("tenantFilter");
            filter.setParameter("companyId", companyId);
            enabledHere = true;
        }
        DEPTH.set(depth + 1);

        try {
            return pjp.proceed();  // 원래 메서드 실행
        } finally {
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
                if (enabledHere) session.disableFilter("tenantFilter");
            } else {
                DEPTH.set(next);
            }
        }
    }
}
```

**이게 무슨 뜻인가요?**

1. `TenantEntity`에 `@Filter(name = "tenantFilter", condition = "company_id = :companyId")`가 선언되어 있습니다.
2. 서비스 메서드에 `@TenantFiltered`를 붙이면, 그 메서드에서 나가는 모든 SELECT 쿼리에 자동으로 `WHERE company_id = ?`가 추가됩니다.
3. 개발자가 `vehicleRepository.findAll()`을 해도, A회사 사용자면 A회사 차량만 나옵니다.

**`DEPTH` ThreadLocal은 왜 있나요?** Service A가 Service B를 호출하고, 둘 다 `@TenantFiltered`이면 Filter가 두 번 활성화됩니다. 이미 활성화된 상태에서 또 활성화하면 에러가 날 수 있으므로, 최초 한 번만 활성화하고 카운팅합니다.

**사용 예시:**

```java
@Service
@TenantFiltered  // 이 클래스의 모든 메서드에 테넌트 필터 적용
public class VehicleQueryService {

    public Page<VehicleListResponse> getVehicles(...) {
        // 여기서 실행되는 모든 쿼리에 자동으로 WHERE company_id = ? 가 붙음!
        return vehicleRepository.findAll(spec, pageable);
    }
}
```

---

## 9. Level 8 — 비동기 처리와 OCR 작업 큐

**이 프로젝트에서 가장 복잡하고 고급인 부분입니다.**

### 9-1. 왜 큐가 필요한가요?

문서 OCR은 외부 API(Upstage)를 호출하는데, 이게 느립니다(수 초~수십 초). 사용자가 파일을 업로드할 때마다 기다리게 하면 UX가 나빠집니다.

```
[큐 없이]
사용자 → 파일 업로드 → OCR 호출(10초 대기) → 결과 저장 → 응답
                        ^^^ 사용자가 10초 기다려야 함

[큐 있을 때]
사용자 → 파일 업로드 → 큐에 작업 등록 → 즉시 응답 "접수됐어요!"
                                         ↓
                        백그라운드 Worker → OCR 호출 → 결과 저장 → SSE로 알림
```

### 9-2. 전체 아키텍처

```
[Producer]          [Redis Queue]          [Consumer]
파일 업로드 서비스    ocr:jobs:queue         OcrJobWorker
    │                    │                      │
    ├─ OcrParseJob 저장   │                      │
    ├─ LPUSH jobId ────→  │                      │
    │                    │  ←── BRPOP ─── Poller 스레드
    │                    │                      │
    │                    │               Dispatcher 스레드
    │                    │                      │
    │                    │            ┌─ Worker 스레드 1
    │                    │            ├─ Worker 스레드 2
    │                    │            └─ Worker 스레드 N
```

### 9-3. Redis를 메시지 큐로 — OcrQueueService

```java
@Service
public class OcrQueueService {

    private static final String QUEUE_KEY = "ocr:jobs:queue";
    private static final String DLQ_KEY = "ocr:jobs:dlq";
    private static final String QUEUED_MARKER_PREFIX = "ocr:jobs:queued:";

    public void enqueue(Long jobId, boolean force) {
        String markerKey = QUEUED_MARKER_PREFIX + jobId;

        if (!force) {
            // 중복 enqueue 방지: 같은 작업이 이미 큐에 있으면 skip
            Boolean reserved = redisTemplate.opsForValue()
                    .setIfAbsent(markerKey, "1", Duration.ofHours(6));
            if (!Boolean.TRUE.equals(reserved)) {
                return;  // 이미 큐에 있음
            }
        }

        redisTemplate.opsForList().leftPush(QUEUE_KEY, String.valueOf(jobId));
    }
}
```

**핵심 패턴들:**

1. **Redis List를 큐로 사용**: `LPUSH`(왼쪽 추가) + `BRPOP`(오른쪽에서 꺼내기, 블로킹) = FIFO 큐
2. **setIfAbsent(SETNX)로 중복 방지**: Redis의 원자적 연산으로 같은 작업이 두 번 큐에 들어가는 것을 방지
3. **DLQ(Dead Letter Queue)**: 처리할 수 없는 작업을 별도 큐에 보관 (나중에 분석)

### 9-4. OCR Worker — 가장 복잡한 클래스

```java
@Component
public class OcrJobWorker {

    // 회사별 대기열: 공정한 처리를 위해
    private final ConcurrentHashMap<Long, Queue<Long>> pendingByCompany = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> roundRobinCompanies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inflightTotal = new AtomicInteger(0);
```

**동시성 컬렉션들:**

| 클래스 | 용도 | 왜 이걸 쓰나 |
|---|---|---|
| `ConcurrentHashMap` | 스레드-안전한 Map | 여러 스레드가 동시에 읽고 쓸 수 있음 |
| `ConcurrentLinkedQueue` | 스레드-안전한 큐 | Lock-free 알고리즘으로 빠름 |
| `AtomicInteger` | 스레드-안전한 카운터 | `count++`가 원자적으로 동작 |

**3개의 스레드가 협력:**

```java
@EventListener(ApplicationReadyEvent.class)
public synchronized void startWorker() {
    // 1. Processing Pool: 실제 OCR 처리 스레드들
    processingPool = Executors.newFixedThreadPool(effectiveConcurrency);

    // 2. Poller: Redis에서 작업 꺼내는 스레드
    pollerThread = new Thread(this::pollLoop, "ocr-queue-poller");
    pollerThread.start();

    // 3. Dispatcher: 회사별 라운드로빈으로 공정하게 배분
    dispatcherThread = new Thread(this::dispatchLoop, "ocr-dispatcher");
    dispatcherThread.start();
}
```

**Poller 루프 — Redis에서 작업 꺼내기:**

```java
private void pollLoop() {
    long backoffMs = 1_000;  // 초기 대기 시간
    int consecutiveErrors = 0;

    while (running) {
        try {
            // BRPOP: 작업이 올 때까지 최대 2초 대기 (블로킹)
            String jobIdStr = redisTemplate.opsForList()
                    .rightPop(queueKey, Duration.ofSeconds(2));

            backoffMs = 1_000;       // 성공하면 백오프 초기화
            consecutiveErrors = 0;

            if (jobIdStr == null) continue;  // 타임아웃 (작업 없음)

            // 회사별 대기열에 적재
            Long companyId = jobOpt.get().getCompanyId();
            enqueuePending(companyId, jobId);

        } catch (Exception e) {
            consecutiveErrors++;
            sleepQuietly(backoffMs);
            backoffMs = Math.min(backoffMs * 2, 60_000);  // 지수 백오프
        }
    }
}
```

**지수 백오프(Exponential Backoff)란?** 에러가 나면 1초 대기 → 또 에러나면 2초 → 4초 → 8초... 최대 60초. Redis가 잠깐 죽었을 때 폭풍 같은 재시도를 방지합니다.

**Dispatcher — 회사별 라운드로빈:**

```java
private void dispatchLoop() {
    while (running) {
        // 전역 동시 처리 제한 확인
        if (inflightTotal.get() >= maxConcurrency) {
            sleepQuietly(25);
            continue;
        }

        // 라운드로빈: A회사 → B회사 → C회사 → A회사...
        Long companyId = roundRobinCompanies.poll();
        if (companyId == null) { sleepQuietly(25); continue; }

        // 회사별 동시 처리 제한 확인
        AtomicInteger companyInflight = inflightByCompany
                .computeIfAbsent(companyId, id -> new AtomicInteger(0));
        if (companyInflight.get() >= maxInflightPerCompany) {
            enqueueCompanyForDispatch(companyId);
            continue;
        }

        // Worker 스레드에 작업 할당
        processingPool.execute(() -> {
            try {
                claimAndProcess(jobId);
            } finally {
                inflightTotal.decrementAndGet();
                companyInflight.decrementAndGet();
            }
        });
    }
}
```

**왜 라운드로빈이 필요한가요?** A회사가 100개 문서를 올리고, B회사가 2개 올렸을 때, A회사 작업만 먼저 다 처리하면 B회사는 한참 기다려야 합니다. 라운드로빈으로 돌아가면서 처리하면 공정합니다.

### 9-5. 외부 API 호출 제한 — UpstageCallGuard

```java
@Component
public class UpstageCallGuard {

    private Semaphore global;                           // 전역 동시 호출 수 제한
    private final ConcurrentHashMap<Long, Semaphore> perCompany;  // 회사별 제한

    public <T> T run(Long companyId, Supplier<T> action) {
        Semaphore company = companySem(companyId);
        boolean g = false, c = false;

        try {
            g = global.tryAcquire(60, TimeUnit.SECONDS);    // 전역 permit 획득
            if (!g) throw new IllegalStateException("전역 permit 타임아웃");

            c = company.tryAcquire(60, TimeUnit.SECONDS);   // 회사 permit 획득
            if (!c) throw new IllegalStateException("회사 permit 타임아웃");

            return action.get();  // 실제 API 호출
        } finally {
            if (c) company.release();  // 반드시 반납!
            if (g) global.release();
        }
    }
}
```

**Semaphore란?** 동시에 사용할 수 있는 "허가증" 개수를 제한하는 도구입니다. Upstage API가 초당 1건만 처리할 수 있으면, Semaphore(1)로 설정해서 동시에 1건만 호출하게 합니다.

**`Supplier<T> action`이란?** Java의 함수형 인터페이스입니다. "실행할 코드 자체"를 파라미터로 넘기는 겁니다:

```java
// 사용하는 쪽
String result = guard.run(companyId, () -> {
    return upstageService.callOcrApi(fileData);  // 이 코드가 Semaphore 보호 하에 실행됨
});
```

### 9-6. Stale Job 복구

```java
@Scheduled(fixedDelay = 300_000, initialDelay = 60_000)  // 5분마다 실행
public void recoverStaleJobs() {
    // 1) QUEUED 상태로 남아있는 작업 → 큐에 다시 넣기 (지수 백오프 적용)
    List<OcrParseJob> staleQueued = jobRepo.findAllSystemWideByStatus(OcrJobStatus.QUEUED, ...);
    for (OcrParseJob job : staleQueued) {
        if (shouldRecoverNow(job.getId())) {
            ocrQueueService.enqueue(job.getId(), true);
        }
    }

    // 2) 10분 이상 PROCESSING인 작업 → 멈춘 걸로 간주, FAILED 처리
    Instant threshold = Instant.now().minus(Duration.ofMinutes(10));
    List<OcrParseJob> stuck = jobRepo.findAllSystemWideStuckJobs(OcrJobStatus.PROCESSING, threshold);
    for (OcrParseJob job : stuck) {
        job.markFailed("Stuck in PROCESSING for over 10 minutes (auto-recovered)");
        jobRepo.save(job);
    }
}
```

**운영에서 꼭 필요한 패턴입니다.** 서버가 갑자기 재시작되면 PROCESSING 중이던 작업은 영원히 완료되지 않습니다. 이런 "고아 작업"을 주기적으로 찾아서 복구합니다.

---

## 10. Level 9 — SSE 실시간 알림

### 10-1. SSE(Server-Sent Events)란?

일반 HTTP: 클라이언트가 요청 → 서버가 응답 → 끝

SSE: 클라이언트가 연결 → 서버가 계속 데이터를 보냄 (단방향 스트림)

```
클라이언트 → GET /api/notifications/stream (연결 유지)
서버 → data: {"type": "OCR_COMPLETE", "vehicleNo": "12가3456"} ← 수시로 push
서버 → data: {"type": "OCR_FAILED", ...}
서버 → : heartbeat  ← 30초마다 (연결 유지용)
```

### 10-2. SseHub — 연결 관리

```java
@Component
public class SseHub {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_EMITTERS_PER_USER = 3;

    // 사용자별 SSE 연결 목록
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long companyId, Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        String k = companyId + ":" + userId;

        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(k, kk -> new CopyOnWriteArrayList<>());

        // 유저당 최대 3개 연결 (탭 여러 개 열었을 때)
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = list.remove(0);
            if (oldest != null) oldest.complete();
        }

        list.add(emitter);

        // 연결 종료 시 자동 정리
        emitter.onCompletion(() -> remove(k, emitter));
        emitter.onTimeout(() -> remove(k, emitter));
        emitter.onError(e -> remove(k, emitter));

        return emitter;
    }
```

**`CopyOnWriteArrayList`란?** 읽기가 많고 쓰기가 적을 때 좋은 스레드-안전 리스트입니다. 쓰기(add/remove) 시 전체 배열을 복사하므로 쓰기가 느리지만, 읽기는 Lock 없이 빠릅니다. SSE 연결은 자주 생성/삭제되지 않으니 적합합니다.

**Heartbeat:**

```java
@Scheduled(fixedRate = 30_000)  // 30초마다
public void heartbeat() {
    emitters.forEach((key, list) -> {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                dead.add(emitter);  // 전송 실패 = 연결 끊김
            }
        }
        dead.forEach(em -> remove(key, em));
    });
}
```

**왜 heartbeat가 필요한가요?** Nginx 같은 리버스 프록시나 브라우저가 일정 시간 데이터가 안 오면 연결을 끊습니다. 30초마다 빈 메시지를 보내서 "아직 살아있어!"라고 알려줍니다.

**비동기 push:**

```java
@Async("ssePushExecutor")  // 별도 스레드풀에서 실행
public void push(Long companyId, Long userId, String event, Object data) {
    // SSE로 클라이언트에 데이터 전송
}
```

**왜 `@Async`인가요?** OCR 처리 완료 후 알림을 보내는데, 클라이언트 네트워크가 느리면 SSE 전송이 오래 걸릴 수 있습니다. DB 트랜잭션이 SSE 전송을 기다리면 안 되니까, 별도 스레드에서 비동기로 처리합니다.

---

## 11. Level 10 — 운영 수준 기법들

### 11-1. MDC 로깅 — 요청 추적

```java
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(...) {
        try {
            // 1) 요청별 고유 ID
            String requestId = resolveRequestId(request.getHeader("X-Request-Id"));
            MDC.put("requestId", requestId);

            // 2) 회사 ID, 사용자 ID
            MDC.put("companyId", TenantContext.getCompanyId().toString());
            MDC.put("userId", auth.getName());

            // 3) 클라이언트 IP (프록시 고려)
            MDC.put("clientIp", resolveClientIp(request));

            // 응답 헤더에도 포함 (프론트엔드 디버깅용)
            response.setHeader("X-Request-Id", requestId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();  // 반드시 정리!
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        // X-Forwarded-For: 프록시를 거친 경우 원래 IP
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
```

**MDC(Mapped Diagnostic Context)란?** 로그에 자동으로 추가 정보를 넣어주는 기능입니다.

```
// MDC 없이
2024-01-15 ERROR 차량 조회 실패  ← 누가 요청한 건지 모름

// MDC 있으면
2024-01-15 ERROR [reqId=a3f8b2] [company=1] [user=hong] 차량 조회 실패
                  ← 어떤 요청인지, 어느 회사인지, 누구인지 바로 알 수 있음
```

### 11-2. 전역 예외 처리 — GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustom(CustomException ex) {
        ErrorCode ec = ex.getErrorCode();
        return ResponseEntity.status(ec.getStatus())
                .body(ErrorResponse.builder()
                        .code(ec.getCode())
                        .message(ex.getDetail() != null ? ex.getDetail() : ec.getMessage())
                        .status(ec.getStatus().value())
                        .build());
    }
}
```

**`@RestControllerAdvice`란?** 모든 Controller에서 발생하는 예외를 한 곳에서 처리합니다. 각 Controller마다 try-catch 쓸 필요 없이, 비즈니스 코드에서 `throw new CustomException(ErrorCode.NOT_FOUND)` 하면 자동으로 적절한 JSON 응답이 나갑니다.

### 11-3. Docker Compose — 인프라 한 번에 띄우기

```yaml
version: "3.8"
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: cafe
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1"]
      interval: 10s

  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]

  app:
    build: .                    # Dockerfile로 빌드
    depends_on:
      mysql:
        condition: service_healthy   # MySQL이 준비된 후에 시작
      redis:
        condition: service_healthy   # Redis가 준비된 후에 시작
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DATABASE_URL: jdbc:mysql://mysql:3306/cafe
```

**`depends_on` + `condition: service_healthy`**: 단순히 "mysql이 먼저 시작"이 아니라, "mysql이 healthcheck를 통과해야(=실제로 쿼리를 받을 준비가 되어야)" 앱을 시작합니다.

### 11-4. 프로파일 분리 (개발 vs 운영)

```yaml
# application.yaml (개발)
spring:
  datasource:
    url: jdbc:h2:mem:cafe        # 인메모리 DB
  jpa:
    ddl-auto: create             # 테이블 자동 생성
    show-sql: true               # SQL 출력

# application-prod.yaml (운영)
spring:
  datasource:
    url: ${DATABASE_URL}         # 환경변수에서 DB URL 주입
  jpa:
    ddl-auto: none               # 테이블 건드리지 않음
    show-sql: false              # SQL 출력 끔
```

`SPRING_PROFILES_ACTIVE=prod`로 실행하면 `application.yaml` + `application-prod.yaml`이 합쳐지면서 prod 설정이 우선 적용됩니다.

---

## 12. 기술 전체 요약 맵

```
난이도 ★☆☆☆☆ (기초)
├── Spring Boot 프로젝트 구조, main 메서드
├── Lombok (@Getter, @RequiredArgsConstructor, @Slf4j)
├── Java record (DTO용 불변 데이터 클래스)
├── application.yaml 설정
└── build.gradle 의존성 관리

난이도 ★★☆☆☆ (초급)
├── Controller → Service → Repository 계층 구조
├── DTO Request/Response 분리
├── Spring Data JPA (인터페이스만으로 CRUD)
├── @Transactional
├── Enum으로 상태 관리
└── Soft Delete 패턴

난이도 ★★★☆☆ (중급)
├── JPA Auditing (@CreatedDate, @LastModifiedDate)
├── BaseEntity 상속 패턴
├── JWT 토큰 발급/검증
├── Spring Security Filter Chain
├── CORS 설정
├── AWS S3 파일 업로드
├── GlobalExceptionHandler (전역 예외 처리)
├── ErrorCode Enum
└── JPA Specification (동적 쿼리)

난이도 ★★★★☆ (고급)
├── JWT + Refresh Token + HttpOnly Cookie 전략
├── Redis — Rate Limiting (로그인 보안)
├── Redis — Caching (@Cacheable)
├── Redis — 메시지 큐 (LPUSH + BRPOP)
├── SSE (Server-Sent Events) 실시간 알림
├── 멀티테넌시 — ThreadLocal + EntityListener + Hibernate Filter
├── AOP (@Aspect) — TenantFilterAspect
├── @Async 비동기 처리
├── Docker Compose (MySQL + Redis + App)
└── MDC 로깅

난이도 ★★★★★ (시니어)
├── OCR Job Worker (Poller + Dispatcher + Worker Pool)
├── 회사별 라운드로빈 공정 스케줄링
├── Semaphore 기반 API Rate Limiter (UpstageCallGuard)
├── 지수 백오프 + Stale Job 복구
├── 동시성 컬렉션 (ConcurrentHashMap, AtomicInteger)
├── DLQ (Dead Letter Queue)
├── fail-open 패턴 (Redis 장애 대응)
└── 보안 헤더 (CSP, HSTS, Referrer-Policy)
```

---

> **마치며**: 이 프로젝트는 단순 CRUD를 넘어서 멀티테넌시, 비동기 작업 큐, 실시간 알림, 보안까지 실무에서 마주치는 대부분의 백엔드 기술을 다루고 있습니다. 한 번에 다 이해하려 하지 말고, Level 1부터 차근차근 코드를 직접 읽으면서 "이 부분이 왜 이렇게 되어 있지?"를 질문하며 학습하는 것을 추천합니다.
