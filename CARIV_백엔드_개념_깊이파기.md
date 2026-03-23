# CARIV 백엔드에 사용된 기술 — 개념 깊이 파기

> 이 문서는 프로젝트 흐름이 아니라, 프로젝트에 사용된 **각 기술/개념의 내부 동작 원리**를 깊이 있게 다룹니다.
> "이게 뭐야?"가 아니라 **"이게 안에서 어떻게 돌아가는 거야?"** 에 대한 답입니다.

---

# Part 1. 네트워크와 프로토콜 계층

---

## 1-1. TCP/IP — 모든 통신의 기반

### 핵심 질문: 브라우저에서 서버로 데이터가 어떻게 도달하는가?

인터넷 통신은 **4계층 모델**로 동작한다.

```
[애플리케이션 계층]  HTTP, HTTPS, SSE, WebSocket  ← 개발자가 다루는 영역
[전송 계층]          TCP, UDP                      ← OS가 관리
[인터넷 계층]        IP                            ← 라우터가 관리
[네트워크 접근 계층]  이더넷, Wi-Fi                  ← 하드웨어
```

### TCP의 핵심 — 신뢰성 있는 연결

TCP(Transmission Control Protocol)는 데이터가 **순서대로, 빠짐없이, 중복 없이** 도착하는 것을 보장한다.

**3-Way Handshake (연결 수립)**

```
클라이언트                    서버
    │                          │
    │──── SYN (seq=100) ──────→│   ① "연결하고 싶어" (SYN 패킷 전송)
    │                          │
    │←── SYN+ACK (seq=300,     │   ② "OK, 나도 준비됐어" (SYN+ACK 응답)
    │     ack=101) ────────────│
    │                          │
    │──── ACK (ack=301) ──────→│   ③ "확인, 연결 시작하자" (ACK 전송)
    │                          │
    │     [연결 수립 완료]        │
```

- **SYN**: "나 연결 시작할게, 내 시퀀스 번호는 100이야"
- **SYN+ACK**: "알겠어, 내 시퀀스 번호는 300이고, 네 101번을 기다릴게"
- **ACK**: "확인, 네 301번을 기다릴게"

이 시퀀스 번호(seq)와 확인 번호(ack)가 TCP의 핵심이다. 모든 바이트에 번호가 매겨져 있어서, 중간에 패킷이 유실되면 "몇 번부터 다시 보내줘"라고 요청할 수 있다.

**4-Way Handshake (연결 종료)**

```
클라이언트                    서버
    │──── FIN ────────────────→│   ① "나 보낼 거 다 보냈어"
    │←──── ACK ────────────────│   ② "알겠어"
    │                          │
    │←──── FIN ────────────────│   ③ "나도 다 보냈어"
    │──── ACK ────────────────→│   ④ "확인"
```

종료가 4단계인 이유: 서버가 ACK를 보낸 뒤에도 아직 보낼 데이터가 남아있을 수 있기 때문에, "확인"과 "종료"를 분리한다.

### 소켓(Socket)이란?

소켓은 TCP 연결의 **끝점(endpoint)**이다. 프로그래밍에서 네트워크 통신을 하려면 소켓을 열어야 한다.

```
소켓 = (IP주소, 포트번호) 의 조합
예: (192.168.1.10, 8080)

하나의 TCP 연결 = (클라이언트 IP:포트, 서버 IP:포트) 4개의 값으로 식별
예: (192.168.1.10:52341, 10.0.0.5:8080)
```

서버 소켓의 동작:

```
1. socket()     → 소켓 생성
2. bind()       → IP:포트에 바인딩 (예: 0.0.0.0:8080)
3. listen()     → 연결 대기 상태로 전환
4. accept()     → 클라이언트 연결 수락 → 새 소켓 반환 (이 소켓으로 통신)
5. read/write   → 데이터 주고받기
6. close()      → 소켓 닫기
```

**핵심**: `accept()`가 호출되면 새로운 소켓이 반환된다. 원래 서버 소켓은 계속 `listen` 상태를 유지하고, 실제 통신은 새 소켓에서 한다. 이래야 동시에 여러 클라이언트를 처리할 수 있다.

### 이 프로젝트에서의 적용

Tomcat(WAS)이 내부적으로 `ServerSocket.accept()`를 호출하면서 8080 포트에서 TCP 연결을 대기한다. 클라이언트가 연결하면 새 소켓을 만들어 스레드 풀의 스레드에 할당하고, 그 스레드가 HTTP 요청을 읽고 처리한다.

---

## 1-2. HTTP 프로토콜 — 요청과 응답의 구조

### HTTP 메시지의 실제 모습

브라우저가 보내는 실제 바이트를 풀어보면 이런 텍스트다:

```
POST /api/auth/login HTTP/1.1          ← 요청 라인 (메서드 + URI + 버전)
Host: localhost:8080                    ← 헤더 시작
Content-Type: application/json          ← "본문이 JSON이야"
Authorization: Bearer eyJhbGci...       ← JWT 토큰
Content-Length: 52                      ← 본문 길이
                                        ← 빈 줄 (헤더와 본문 구분)
{"loginId":"hong","password":"1234"}    ← 본문 (Body)
```

서버의 응답:

```
HTTP/1.1 200 OK                         ← 상태 라인
Content-Type: application/json
X-Request-Id: a3f8b2c1d4e5
Set-Cookie: refresh_token=eyJ...; HttpOnly; Path=/; SameSite=Lax
                                         ← 빈 줄
{"accessToken":"eyJhbGci..."}            ← 본문
```

### HTTP의 근본적 특성 — Stateless(무상태)

HTTP는 **각 요청이 독립적**이다. 서버는 이전 요청을 기억하지 않는다.

```
요청1: POST /api/auth/login → 서버: "인증 성공" → 응답
요청2: GET /api/vehicles   → 서버: "누구세요?" → 401 Unauthorized
```

요청1에서 로그인했지만, 요청2에서 서버는 그걸 모른다. 그래서 **매 요청마다 "나 이 사람이야"라고 알려주는 방법**이 필요하다:

- **세션 방식**: 서버가 메모리에 로그인 상태를 저장하고, 클라이언트에 세션ID 쿠키를 줌
- **토큰 방식**: 클라이언트가 매 요청마다 JWT 토큰을 헤더에 넣어서 보냄 ← 이 프로젝트

### HTTP/1.1 Keep-Alive

HTTP/1.0은 요청마다 TCP 연결을 새로 맺었다. 3-Way Handshake가 매번 발생하므로 느리다.

```
HTTP/1.0:
연결 → 요청 → 응답 → 종료 → 연결 → 요청 → 응답 → 종료  (매번 핸드셰이크)

HTTP/1.1 Keep-Alive:
연결 → 요청 → 응답 → 요청 → 응답 → 요청 → 응답 → 종료  (하나의 연결 재사용)
```

이 프로젝트의 Tomcat은 HTTP/1.1을 사용하므로 Keep-Alive가 기본 활성화되어 있다.

---

## 1-3. SSE (Server-Sent Events) — 단방향 실시간 통신

### 이 프로젝트에서 SSE를 쓰는 곳

`SseHub.java` — OCR 처리 완료 시 클라이언트에 실시간 알림을 보낸다.

### SSE의 프로토콜 수준 동작 원리

**① 클라이언트가 SSE 연결을 요청한다**

```
GET /api/notifications/stream HTTP/1.1
Accept: text/event-stream              ← "SSE 스트림을 받고 싶어"
Cache-Control: no-cache                ← "캐시하지 마"
Connection: keep-alive
```

**② 서버가 응답을 보내되, 연결을 끊지 않는다**

```
HTTP/1.1 200 OK
Content-Type: text/event-stream        ← "이건 SSE 스트림이야"
Cache-Control: no-cache
Connection: keep-alive
Transfer-Encoding: chunked             ← "데이터를 조금씩 보낼게" (핵심!)
                                        ← 여기서 끊기지 않음!
```

**③ 서버가 이벤트를 보낸다**

```
event: connected                        ← 이벤트 타입
data: ok                                ← 이벤트 데이터

event: ocr-complete                     ← (시간이 지난 후)
data: {"vehicleNo":"12가3456","status":"SUCCESS"}

: heartbeat                             ← ':'으로 시작 = 주석 (클라이언트 무시)
                                         ← 30초마다 전송하여 연결 유지
```

### SSE vs WebSocket vs Polling

```
                  SSE              WebSocket          Polling
방향              단방향 (서버→클라)  양방향              클라→서버 반복
프로토콜          HTTP 위에서 동작    별도 프로토콜         HTTP 반복 요청
연결              HTTP 연결 유지      TCP 위 별도 프레임    매번 새 요청
복잡도            낮음               높음                낮지만 비효율
자동 재연결        브라우저 내장        직접 구현 필요        해당없음
방화벽/프록시       HTTP라서 통과 쉬움  별도 포트 필요할 수도  문제없음
```

**이 프로젝트에서 SSE를 선택한 이유**: OCR 결과 알림은 서버→클라이언트 단방향이면 충분하고, HTTP 기반이라 인프라 설정이 간단하다.

### Transfer-Encoding: chunked — SSE의 비밀

일반 HTTP 응답은 `Content-Length: 52`처럼 본문 길이를 미리 알려주고, 그만큼 보내면 끝이다. 하지만 SSE는 **언제 끝날지 모른다**. 이때 사용하는 게 **chunked transfer encoding**이다.

```
일반 HTTP:    [헤더] [Content-Length: 52] [본문 52바이트] → 끝!
chunked HTTP: [헤더] [chunk1] [chunk2] [chunk3] ... [0 크기 chunk] → 끝!
```

각 chunk는 `크기(16진수)\r\n데이터\r\n` 형식이다. 크기가 0인 chunk가 오면 "전송 완료"를 의미하지만, SSE는 이 종료 chunk를 보내지 않고 연결을 열어둔다.

### SseEmitter의 내부 동작

Spring의 `SseEmitter`가 내부적으로 하는 일:

```
1. Controller가 SseEmitter를 반환
2. Spring MVC가 HTTP 응답을 "Content-Type: text/event-stream"으로 설정
3. 응답을 flush하되 연결은 유지 (Servlet 3.0 Async 기반)
4. emitter.send() 호출 시:
   - "event: xxx\ndata: yyy\n\n" 형식으로 변환
   - response.getWriter().write() + flush()
   - 이때 Servlet의 AsyncContext가 사용됨
5. emitter.complete() 또는 timeout 시 연결 종료
```

**중요한 포인트 — Servlet Async**:
일반 서블릿은 요청을 받으면 스레드가 응답이 끝날 때까지 점유된다. SSE는 연결이 30분간 유지되므로, 스레드 하나가 30분간 점유되면 스레드 풀이 고갈된다.

Servlet 3.0의 **AsyncContext**는 "응답을 나중에 보낼게"라고 선언하고 스레드를 반환한다. 실제 데이터를 보낼 때만 잠깐 스레드를 빌려쓴다.

```
[일반 요청]
스레드 할당 → 처리 → 응답 → 스레드 반환 (수 ms)

[SSE 요청]
스레드 할당 → AsyncContext 시작 → 스레드 반환 (즉시!)
                                  ↓
           (나중에) push 이벤트 → 잠깐 스레드 빌려서 write → 스레드 반환
           (나중에) push 이벤트 → 잠깐 스레드 빌려서 write → 스레드 반환
```

이래서 `SecurityConfig`에 `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()`이 있는 것이다. Async 디스패치는 이미 인증된 요청의 연장이므로 다시 인증할 필요가 없다.

---

# Part 2. 서블릿과 WAS

---

## 2-1. 서블릿(Servlet)의 내부 구조

### 서블릿이 해결하는 문제

서버가 HTTP 요청을 처리하려면:

```
1. TCP/IP 연결 대기 및 소켓 수락
2. HTTP 요청 메시지 바이트를 읽기
3. 요청 라인 파싱 (GET /api/vehicles HTTP/1.1)
4. 헤더 파싱 (Content-Type, Authorization 등)
5. 본문 읽기 및 파싱
6. ──────── 비즈니스 로직 실행 ────────  ← 이것만 개발자가 한다
7. HTTP 응답 메시지 생성
8. 응답 헤더 작성
9. 응답 본문 직렬화
10. 바이트로 변환하여 소켓에 쓰기
11. 소켓 종료/관리
```

1~5번과 7~11번은 **모든 웹 애플리케이션에서 똑같은 작업**이다. 이걸 매번 직접 구현하면 낭비다. 서블릿은 이 반복 작업을 WAS가 대신하고, 개발자는 6번만 집중하게 해주는 **표준 인터페이스(Specification)**다.

### 서블릿의 인터페이스

```java
public interface Servlet {
    void init(ServletConfig config);                         // 초기화 (1회)
    void service(ServletRequest req, ServletResponse res);   // 요청 처리 (매번)
    void destroy();                                          // 종료 (1회)
    ServletConfig getServletConfig();
    String getServletInfo();
}
```

실제로는 `HttpServlet`을 상속한다:

```java
public abstract class HttpServlet extends GenericServlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp);
    protected void doPost(HttpServletRequest req, HttpServletResponse resp);
    protected void doPut(HttpServletRequest req, HttpServletResponse resp);
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp);
}
```

### HttpServletRequest의 내부

WAS가 TCP 소켓에서 읽은 바이트를 파싱하여 이 객체를 채운다:

```java
// WAS가 내부적으로 하는 일 (개발자는 안 해도 됨):
// "POST /api/auth/login HTTP/1.1\r\nContent-Type: application/json\r\n..."
// 이 문자열을 파싱해서:

request.getMethod()          → "POST"
request.getRequestURI()      → "/api/auth/login"
request.getProtocol()        → "HTTP/1.1"
request.getHeader("Content-Type")  → "application/json"
request.getHeader("Authorization") → "Bearer eyJhb..."
request.getInputStream()     → 본문 바이트 스트림
request.getRemoteAddr()      → "192.168.1.10" (클라이언트 IP)
request.getParameter("name") → 쿼리파라미터 or form 데이터
```

**Request 객체는 요청마다 새로 생성된다.** 1000개 동시 요청이면 1000개의 Request 객체가 존재한다.

### HttpServletResponse의 내부

개발자가 이 객체에 값을 넣으면, WAS가 HTTP 응답 바이트로 변환하여 소켓에 쓴다:

```java
response.setStatus(200);
response.setContentType("application/json");
response.setCharacterEncoding("UTF-8");
response.setHeader("X-Request-Id", "a3f8b2");

PrintWriter writer = response.getWriter();
writer.write("{\"accessToken\":\"eyJhb...\"}");

// WAS가 이걸 아래 바이트로 변환:
// "HTTP/1.1 200 OK\r\nContent-Type: application/json;charset=UTF-8\r\n..."
```

### 서블릿 생명주기

```
[WAS 시작]
    │
    ├── 서블릿 클래스 로딩 (ClassLoader)
    ├── 서블릿 인스턴스 생성 (딱 1개 — 싱글톤!)
    ├── init() 호출 (1번만)
    │
    │   [요청1 도착] → service() → doPost() → 스레드1에서 실행
    │   [요청2 도착] → service() → doGet()  → 스레드2에서 실행
    │   [요청3 도착] → service() → doPost() → 스레드3에서 실행
    │       ↑ 같은 서블릿 인스턴스를 여러 스레드가 공유!
    │
[WAS 종료]
    ├── destroy() 호출 (1번만)
    └── 인스턴스 GC
```

**싱글톤이므로 인스턴스 변수(필드)에 상태를 저장하면 안 된다.** 스레드1이 쓴 값을 스레드2가 덮어쓰는 **race condition**이 발생한다. 이것이 `ThreadLocal`이 필요한 이유 중 하나다.

### 서블릿 컨테이너 (= WAS = Tomcat)

서블릿 컨테이너가 하는 일:

```
1. 서블릿 생명주기 관리 (생성, 초기화, 호출, 종료)
2. 소켓 리스닝 및 TCP 연결 관리
3. HTTP 요청 파싱 → Request 객체 생성
4. 스레드 풀 관리 (동시 요청 처리)
5. 서블릿 매핑 (URL → 어떤 서블릿 호출?)
6. 필터 체인 실행
7. Response 객체 → HTTP 응답 바이트 변환 및 전송
8. Keep-Alive 연결 관리
```

Spring Boot에서는 Tomcat이 **내장(embedded)** 되어 있다. `SpringApplication.run()`을 호출하면 내부적으로 Tomcat 인스턴스가 생성되고 8080 포트에서 리스닝을 시작한다.

---

## 2-2. 스레드 풀(Thread Pool) — 왜 필요하고 어떻게 동작하는가

### 요청마다 스레드를 새로 만들면?

```
요청 도착 → new Thread() → 처리 → 스레드 종료

문제:
- 스레드 생성 비용: OS 커널 호출 + 스택 메모리 할당(기본 1MB) → 수 ms 소요
- 동시 요청 10,000개 → 스레드 10,000개 → 메모리 10GB + 컨텍스트 스위칭 폭발
- 스레드가 무한히 늘어나면 서버가 죽음 (OOM)
```

### 스레드 풀의 구조

```
┌─────────────────────────────────────┐
│           Thread Pool                │
│                                     │
│  [스레드1] [스레드2] ... [스레드200]   │  ← 미리 생성된 스레드들
│                                     │
│  ┌──────────────────────┐           │
│  │    작업 큐 (Queue)     │           │
│  │ [요청A][요청B][요청C]  │           │  ← 스레드가 모두 바쁘면 여기서 대기
│  └──────────────────────┘           │
└─────────────────────────────────────┘

요청 도착 → 큐에 넣기 → 놀고 있는 스레드가 큐에서 꺼내서 처리 → 완료 → 스레드 반환
```

Tomcat 기본 설정:

```
최소 스레드: 10개 (미리 생성)
최대 스레드: 200개 (동시 200개 요청 처리 가능)
큐 크기: 100 (스레드 200개 다 바쁘면 100개까지 대기)
큐도 가득 차면 → 요청 거부 (503 Service Unavailable)
```

### 이 프로젝트에서의 스레드 풀 활용

1. **Tomcat 스레드 풀**: HTTP 요청 처리 (기본 200개)
2. **OCR Worker Pool**: `Executors.newFixedThreadPool(maxConcurrency)` — OCR 처리 전용
3. **SSE Push Pool**: `AsyncConfig`에서 `ssePushExecutor` — SSE 메시지 전송 전용

왜 풀을 분리하나? OCR 처리가 느려서 Tomcat 스레드를 30초씩 점유하면, 다른 API 요청도 처리 못 한다. 별도 풀로 분리하면 OCR이 바빠도 일반 API에 영향이 없다.

---

## 2-3. 필터(Filter) — 서블릿 앞단의 관문

### 필터 체인의 내부 구조

```
HTTP 요청 → [Filter1] → [Filter2] → [Filter3] → [DispatcherServlet] → [Controller]
HTTP 응답 ← [Filter1] ← [Filter2] ← [Filter3] ← [DispatcherServlet] ← [Controller]
```

필터는 **요청 전처리와 응답 후처리**를 모두 할 수 있다:

```java
public class MyFilter implements Filter {
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        // ===== 요청 전처리 =====
        // 여기서 request를 검사/수정할 수 있다

        chain.doFilter(request, response);  // 다음 필터 또는 서블릿 호출

        // ===== 응답 후처리 =====
        // 여기서 response를 검사/수정할 수 있다
    }
}
```

`chain.doFilter()`를 호출하지 않으면 요청이 더 이상 진행되지 않는다. JWT 필터에서 토큰이 유효하지 않으면 `chain.doFilter()`를 호출하지 않고 직접 에러 응답을 쓰는 이유다.

### 이 프로젝트의 필터 체인

```
요청 → [CorsFilter] → [SecurityFilter] → [JwtAuthenticationFilter] → [MdcFilter] → DispatcherServlet
```

각 필터의 역할:
- **CorsFilter**: Origin 헤더 확인, preflight(OPTIONS) 처리
- **SecurityFilter**: Spring Security의 인가 체크
- **JwtAuthenticationFilter**: 토큰 검증 → SecurityContext에 인증 정보 설정 → TenantContext 설정
- **MdcFilter**: requestId, companyId, userId를 MDC에 넣기 (로깅용)

### OncePerRequestFilter의 존재 이유

Spring은 내부적으로 **forward**나 **error dispatch**를 할 수 있다. 이때 같은 요청이 필터를 다시 타게 된다. `OncePerRequestFilter`는 request attribute에 "이 필터 이미 실행했음" 플래그를 남겨서 중복 실행을 방지한다:

```java
// OncePerRequestFilter 내부 (간략화)
public final void doFilter(request, response, chain) {
    String attrName = getClass().getName() + ".FILTERED";
    if (request.getAttribute(attrName) != null) {
        chain.doFilter(request, response);  // 이미 실행했으면 패스
        return;
    }
    request.setAttribute(attrName, Boolean.TRUE);
    doFilterInternal(request, response, chain);  // 처음이면 실행
}
```

---

# Part 3. Spring 핵심 원리

---

## 3-1. IoC (Inversion of Control) — 제어의 역전

### 전통적인 방식 vs IoC

```java
// 전통적 방식: 내가 직접 의존 객체를 생성
public class AuthService {
    private UserRepository userRepo = new UserRepositoryImpl();  // 내가 만든다
    private PasswordEncoder encoder = new BCryptPasswordEncoder(); // 내가 만든다
}

// IoC 방식: 누군가(Spring)가 만들어서 넣어준다
public class AuthService {
    private final UserRepository userRepo;      // 누가 넣어줄 거야
    private final PasswordEncoder encoder;      // 누가 넣어줄 거야

    public AuthService(UserRepository userRepo, PasswordEncoder encoder) {
        this.userRepo = userRepo;               // 외부에서 주입받는다
        this.encoder = encoder;
    }
}
```

**"제어의 역전"이라는 이름의 의미**: 전통적으로는 내 코드가 의존 객체를 **제어**(생성, 관리)했다. IoC에서는 프레임워크가 그 제어를 **가져간다**(역전). 내 코드는 "이런 게 필요해"라고만 선언하면, Spring이 알아서 만들어 넣어준다.

### DI (Dependency Injection) — IoC를 구현하는 방법

Spring에서 DI를 하는 3가지 방법:

```java
// 1. 생성자 주입 (권장 — 이 프로젝트가 사용하는 방식)
@RequiredArgsConstructor  // Lombok이 생성자를 자동 생성
public class AuthService {
    private final UserRepository userRepo;     // final → 반드시 주입되어야 함
    private final PasswordEncoder encoder;
}

// 2. 세터 주입 (비권장)
public class AuthService {
    private UserRepository userRepo;

    @Autowired
    public void setUserRepo(UserRepository userRepo) {
        this.userRepo = userRepo;
    }
}

// 3. 필드 주입 (비권장)
public class AuthService {
    @Autowired
    private UserRepository userRepo;
}
```

**왜 생성자 주입이 권장되나?**

1. `final` 키워드를 쓸 수 있다 → 한번 주입되면 변경 불가 (불변성 보장)
2. 테스트할 때 Mock 객체를 쉽게 넣을 수 있다
3. 순환 참조를 컴파일 타임에 감지할 수 있다
4. 필수 의존성이 누락되면 앱 시작 시 즉시 에러 (나중에 NPE로 터지는 것보다 낫다)

---

## 3-2. Spring Bean과 싱글톤 컨테이너

### Bean이란?

Spring IoC 컨테이너가 관리하는 객체를 Bean이라고 한다. `@Component`, `@Service`, `@Repository`, `@Controller`, `@Configuration` + `@Bean` 메서드로 등록한다.

### 싱글톤으로 관리되는 이유

```
요청1 → AuthService 필요 → 컨테이너에서 가져옴 → 같은 인스턴스
요청2 → AuthService 필요 → 컨테이너에서 가져옴 → 같은 인스턴스 (동일 객체!)
```

서블릿과 마찬가지 논리다. 요청마다 Service 객체를 새로 만들면 메모리 낭비이고, Service는 **상태(state)가 없는 순수한 로직 컨테이너**이므로 하나만 있으면 된다.

**주의**: 싱글톤이므로 인스턴스 변수에 요청별 데이터를 저장하면 안 된다:

```java
@Service
public class BadService {
    private String currentUser;  // ← 위험! 모든 스레드가 공유

    public void process(String user) {
        this.currentUser = user;  // 스레드A가 "hong"을 넣었는데
        // ...                    // 스레드B가 "kim"으로 덮어쓸 수 있음
        log.info(currentUser);    // 스레드A가 읽으면 "kim"이 나올 수 있음 → 버그!
    }
}
```

이것이 `TenantContext`가 `ThreadLocal`을 사용하는 이유다 (Part 7에서 상세히 다룸).

### Bean 생명주기

```
1. @ComponentScan으로 Bean 후보 탐색
2. BeanDefinition 생성 (메타데이터)
3. 인스턴스 생성 (생성자 호출)
4. 의존성 주입 (생성자 파라미터에 다른 Bean 주입)
5. @PostConstruct 메서드 호출 (초기화)   ← JwtTokenProvider.initSigningKey()
6. Bean 사용 가능
   ... (애플리케이션 실행 중) ...
7. @PreDestroy 메서드 호출 (정리)       ← OcrJobWorker.stop()
8. Bean 소멸
```

이 프로젝트에서:
- `@PostConstruct`: `JwtTokenProvider`가 서명 키를 미리 생성, `UpstageCallGuard`가 Semaphore 초기화
- `@PreDestroy`: `OcrJobWorker`가 스레드 풀을 gracefully 종료

---

## 3-3. AOP (Aspect-Oriented Programming) — 관점 지향 프로그래밍

### AOP가 해결하는 문제

로깅, 트랜잭션, 보안 체크 같은 **횡단 관심사(cross-cutting concern)**가 모든 서비스에 반복된다:

```java
// AOP 없이
public class VehicleService {
    public Vehicle getVehicle(Long id) {
        log.info("getVehicle 시작");           // 로깅
        checkTenant();                          // 테넌트 확인
        Transaction tx = beginTransaction();    // 트랜잭션 시작
        try {
            Vehicle v = repo.findById(id);      // ← 실제 비즈니스 로직은 이것뿐!
            tx.commit();
            return v;
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            log.info("getVehicle 종료");
        }
    }
}
```

비즈니스 로직 1줄에 부가 로직이 10줄이다. AOP는 이 부가 로직을 **별도 클래스(Aspect)**로 분리한다.

### AOP의 동작 원리 — 프록시 패턴

Spring AOP는 **프록시(Proxy) 객체**를 만들어서 원본 객체를 감싼다:

```
[컨트롤러] → [프록시 VehicleService] → [진짜 VehicleService]
                    ↓
            1. 테넌트 필터 활성화 (Before)
            2. 진짜 메서드 호출
            3. 테넌트 필터 비활성화 (After)
```

프록시를 만드는 방법:

```
1. JDK Dynamic Proxy: 인터페이스가 있는 경우, java.lang.reflect.Proxy 사용
2. CGLIB Proxy: 인터페이스가 없는 경우, 바이트코드를 조작하여 서브클래스 생성

Spring Boot 기본: CGLIB Proxy
```

CGLIB은 런타임에 원본 클래스를 **상속**하는 서브클래스를 만든다:

```java
// Spring이 내부적으로 생성하는 프록시 (개념적)
public class VehicleQueryService$$EnhancerByCGLIB extends VehicleQueryService {

    @Override
    public Page<VehicleListResponse> getVehicles(...) {
        // AOP advice 실행 (TenantFilterAspect.around)
        tenantFilter.enable();
        try {
            return super.getVehicles(...);  // 원본 메서드 호출
        } finally {
            tenantFilter.disable();
        }
    }
}
```

### 이 프로젝트의 AOP 사용 — TenantFilterAspect

```java
@Aspect
@Component
public class TenantFilterAspect {

    @Around("@annotation(TenantFiltered) || @within(TenantFiltered)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        // Before: Hibernate Filter 활성화
        session.enableFilter("tenantFilter").setParameter("companyId", companyId);

        try {
            return pjp.proceed();  // 원본 메서드 실행
        } finally {
            // After: Hibernate Filter 비활성화
            session.disableFilter("tenantFilter");
        }
    }
}
```

**AOP 용어 정리:**

```
Aspect     = 횡단 관심사를 모듈화한 클래스 (TenantFilterAspect)
Advice     = 실제 부가 기능 (@Around 메서드)
JoinPoint  = Advice가 적용될 수 있는 지점 (메서드 실행 시점)
Pointcut   = 어떤 JoinPoint에 Advice를 적용할지 결정하는 표현식
             "@annotation(TenantFiltered)" = @TenantFiltered 어노테이션이 붙은 메서드
Target     = 원본 객체 (VehicleQueryService)
Proxy      = AOP가 만든 감싸는 객체
```

### @Transactional도 AOP다

```java
@Transactional
public void createVehicle(VehicleCreateRequest request) {
    // 이 메서드도 프록시로 감싸짐:
    // Before: EntityManager.getTransaction().begin()
    // 메서드 실행
    // 정상 완료: commit()
    // 예외 발생: rollback()
}
```

`@Transactional`이 AOP라는 사실은 중요한 함정을 만든다:

```java
@Service
public class MyService {

    @Transactional
    public void methodA() { ... }

    public void methodB() {
        this.methodA();  // ← 트랜잭션이 동작하지 않음!
    }
}
```

왜? `methodB()`에서 `this.methodA()`를 호출하면, 프록시를 거치지 않고 **원본 객체의 메서드를 직접** 호출한다. 프록시는 **외부에서** 호출할 때만 작동한다.

---

## 3-4. DispatcherServlet — Spring MVC의 핵심

### 프론트 컨트롤러 패턴

모든 HTTP 요청이 하나의 서블릿(`DispatcherServlet`)을 거친다:

```
요청 → [DispatcherServlet]
            │
            ├── HandlerMapping: "이 URL은 어떤 Controller가 처리하지?"
            │   └── /api/vehicles → VehicleManagementController.getVehicles()
            │
            ├── HandlerAdapter: "이 Controller 메서드를 어떻게 호출하지?"
            │   ├── @RequestBody → JSON → Java 객체 변환 (Jackson)
            │   ├── @PathVariable → URL에서 값 추출
            │   └── @RequestParam → 쿼리파라미터 추출
            │
            ├── Controller 메서드 실행
            │
            ├── ReturnValueHandler: "반환값을 어떻게 응답으로 바꾸지?"
            │   └── ResponseEntity<TokenResponse> → JSON 직렬화
            │
            └── 응답 전송
```

---

# Part 4. 보안과 암호학

---

## 4-1. BCrypt — 비밀번호 해싱

### 왜 비밀번호를 해시하는가?

DB가 해킹당해도 비밀번호가 노출되지 않도록. 해시는 **단방향 함수**라서 해시값으로 원본을 복원할 수 없다.

### 일반 해시 vs BCrypt

```
SHA-256("password123") → "ef92b778ba..."
SHA-256("password123") → "ef92b778ba..."  ← 같은 입력이면 항상 같은 출력

문제: 공격자가 흔한 비밀번호 수백만 개를 미리 해시해놓은 테이블(Rainbow Table)을 만들면,
      해시값만 보고 원본을 찾을 수 있다.
```

BCrypt의 해결책 — **Salt + 느린 해시**:

```
BCrypt("password123") → "$2a$10$N9qo8uLOickgx2ZMRZoMye.random.salt.and.hash..."
BCrypt("password123") → "$2a$10$Xkw8r2BcLiMjPz.another.different.result..."
                              ↑ 매번 다른 결과! (랜덤 salt가 포함됨)
```

BCrypt 해시값의 구조:

```
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
 ↑  ↑↑  ↑─────────────────────↑↑──────────────────────────────↑
 │  ││  │       Salt (22자)    ││         Hash (31자)           │
 │  ││  └─────────────────────┘└──────────────────────────────┘
 │  │└── Cost Factor (10 = 2^10 = 1024 라운드)
 │  └─── 버전
 └────── BCrypt 식별자
```

**Cost Factor**: 해시 계산을 의도적으로 느리게 만드는 값. `10`이면 2^10 = 1024번 반복 연산한다. 일반 사용자는 로그인할 때 0.1초 더 기다리는 것뿐이지만, 공격자가 수십억 개 비밀번호를 무차별 대입하려면 1024배 더 오래 걸린다.

### 비밀번호 검증 과정

```java
// 이 프로젝트 코드
passwordEncoder.matches(request.password(), user.getPasswordHash())

// 내부적으로:
// 1. 저장된 해시에서 salt와 cost factor를 추출
// 2. 입력된 비밀번호에 같은 salt와 cost factor로 BCrypt 적용
// 3. 결과가 저장된 해시와 같은지 비교
```

---

## 4-2. JWT — 구조와 서명 원리

### JWT의 세 부분

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJob25nIiwiY29tcGFueUlkIjoxfQ.4f2d8c...
|_______ Header ______||_____________ Payload _______________||_ Signature _|
```

**Header** (Base64 디코딩하면):
```json
{
  "alg": "HS256"    // 서명 알고리즘
}
```

**Payload** (Base64 디코딩하면):
```json
{
  "sub": "hong",           // subject (누구인지)
  "type": "access",
  "userId": 1,
  "companyId": 1,          // ← 멀티테넌시의 핵심!
  "role": "ADMIN",
  "iat": 1705000000,       // 발급 시각 (Unix timestamp)
  "exp": 1705001800        // 만료 시각
}
```

**Signature**:
```
HMAC-SHA256(
    base64(header) + "." + base64(payload),
    secretKey
)
```

### HMAC-SHA256 서명의 원리

```
HMAC = Hash-based Message Authentication Code

1. 비밀키(secretKey)와 메시지(header.payload)를 결합
2. SHA-256 해시 함수를 적용
3. 결과 = 서명값

검증:
1. 받은 토큰의 header.payload로 같은 계산을 한다
2. 계산 결과가 토큰에 포함된 signature와 같으면 → 유효
3. 다르면 → 누군가 payload를 변조했거나 비밀키가 다름
```

**왜 이게 안전한가?** 공격자가 payload의 `companyId`를 1에서 2로 바꾸면, signature가 달라진다. 하지만 공격자는 서버의 `secretKey`를 모르므로 새 signature를 만들 수 없다. 서버가 검증하면 "signature 불일치" → 토큰 거부.

### 이 프로젝트의 비밀키 관리

```java
@PostConstruct
private void initSigningKey() {
    byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length < 32) {  // HS256 최소 256bit
        throw new IllegalStateException("JWT_SECRET 길이가 부족합니다");
    }
    this.cachedSigningKey = Keys.hmacShaKeyFor(keyBytes);
}
```

비밀키 길이가 짧으면 무차별 대입(brute force)으로 키를 알아낼 수 있다. 32바이트(256bit) 이상을 강제하고, 64바이트 이상을 권장한다.

### Access Token + Refresh Token 전략의 보안 논리

```
Access Token:
- 수명: 30분 (짧음)
- 저장: JavaScript 메모리 (변수)
- 전송: Authorization 헤더
- 위험: XSS 공격으로 탈취 가능하지만, 30분이면 만료

Refresh Token:
- 수명: 14일 (김)
- 저장: HttpOnly Cookie (JavaScript 접근 불가!)
- 전송: Cookie 헤더 (브라우저가 자동으로 보냄)
- 위험: CSRF 공격에 취약하지만, Refresh 엔드포인트에만 사용
```

**HttpOnly Cookie란?** `document.cookie`로 JavaScript에서 접근할 수 없는 쿠키다. XSS 공격자가 스크립트를 주입해도 쿠키를 읽을 수 없다.

```
Set-Cookie: refresh_token=eyJ...; HttpOnly; Path=/; SameSite=Lax; Secure

HttpOnly  → JavaScript 접근 불가
Secure    → HTTPS에서만 전송
SameSite=Lax → 다른 사이트에서의 요청에 쿠키를 보내지 않음 (CSRF 방지)
```

---

## 4-3. Spring Security Filter Chain — 내부 동작

### Security Filter Chain의 실제 순서

Spring Security는 서블릿 필터 안에 **자체 필터 체인**을 가지고 있다:

```
[서블릿 필터] → [DelegatingFilterProxy] → [FilterChainProxy]
                                              │
                    ┌─────────────────────────┤
                    │ Spring Security Filters  │
                    │                         │
                    │ 1. SecurityContextPersistenceFilter (컨텍스트 복원)
                    │ 2. CorsFilter (CORS 처리)
                    │ 3. LogoutFilter
                    │ 4. UsernamePasswordAuthenticationFilter
                    │ 5. [JwtAuthenticationFilter] ← 이 프로젝트가 추가
                    │ 6. [MdcFilter] ← 이 프로젝트가 추가
                    │ 7. ExceptionTranslationFilter (인증/인가 예외 처리)
                    │ 8. FilterSecurityInterceptor (최종 인가 결정)
                    └─────────────────────────┘
```

### SecurityContext — 인증 정보의 저장소

```java
// JWT 필터에서
SecurityContextHolder.getContext().setAuthentication(authentication);

// 내부 구조:
SecurityContextHolder
    └── SecurityContext (ThreadLocal에 저장)
            └── Authentication
                    ├── Principal: CustomUserDetails (누구인지)
                    ├── Credentials: null (비밀번호는 인증 후 제거)
                    └── Authorities: [ROLE_ADMIN] (권한 목록)
```

`SecurityContextHolder`는 기본적으로 **ThreadLocal** 전략을 사용한다. 각 스레드(=각 요청)마다 독립적인 SecurityContext를 가진다.

### 인가(Authorization)의 두 가지 방식

```java
// 1. URL 기반 (SecurityConfig에서)
auth.requestMatchers("/api/master/**").hasRole("MASTER")
// → FilterSecurityInterceptor가 URL 패턴 매칭 후 권한 체크

// 2. 메서드 기반
@PreAuthorize("hasRole('ADMIN')")
public void deleteUser(Long userId) { ... }
// → AOP 프록시가 메서드 호출 전 권한 체크
```

---

# Part 5. JPA와 Hibernate의 내부

---

## 5-1. 영속성 컨텍스트 (Persistence Context)

### JPA의 핵심 — EntityManager

JPA는 SQL을 직접 다루지 않고, Java 객체(Entity)를 통해 DB를 다룬다. 이 마법의 핵심이 **영속성 컨텍스트**다.

```
┌──────────────────────────────────────────────┐
│           영속성 컨텍스트 (1차 캐시)             │
│                                              │
│  Key(ID)     │  Entity 객체                   │
│  ─────────   │  ─────────                    │
│  Vehicle#1   │  Vehicle{id=1, vin="ABC"}     │
│  Vehicle#2   │  Vehicle{id=2, vin="DEF"}     │
│  User#1      │  User{id=1, loginId="hong"}   │
│                                              │
│  ┌─────────────────────────┐                 │
│  │  SQL 쓰기 지연 저장소      │                 │
│  │  INSERT INTO vehicles.. │                 │
│  │  UPDATE users SET..     │                 │
│  └─────────────────────────┘                 │
└──────────────────────────────────────────────┘
                    │
                    │ flush() (트랜잭션 커밋 시)
                    ↓
              ┌──────────┐
              │ Database  │
              └──────────┘
```

### Entity의 4가지 상태

```
비영속 (new/transient):
  Vehicle v = new Vehicle();           // 그냥 Java 객체, JPA 모름

영속 (managed):
  em.persist(v);                       // 영속성 컨텍스트에 들어감
  Vehicle v = repo.findById(1);        // DB에서 조회 → 영속 상태

준영속 (detached):
  em.detach(v);                        // 영속성 컨텍스트에서 분리
  em.close();                          // 영속성 컨텍스트 닫힘 → 모두 준영속

삭제 (removed):
  em.remove(v);                        // 삭제 예약 (flush 시 DELETE 실행)
```

### Dirty Checking (변경 감지)

JPA의 가장 신기한 기능. **Entity의 필드를 바꾸기만 하면 UPDATE가 자동으로 실행**된다:

```java
@Transactional
public void updateVehicle(Long id, String newVin) {
    Vehicle v = vehicleRepository.findById(id).get();  // 영속 상태
    v.setVin(newVin);                                   // 값만 변경
    // vehicleRepository.save(v);  ← 이걸 안 해도 됨!
}
// 트랜잭션 커밋 시점에 JPA가 자동으로:
// 1. 영속성 컨텍스트의 스냅샷(최초 조회 시점)과 현재 Entity를 비교
// 2. vin이 바뀌었으므로 UPDATE SQL 생성
// 3. flush → DB에 반영
```

**내부 동작:**

```
findById(1) 호출 시:
  → DB에서 SELECT → Vehicle 객체 생성
  → 영속성 컨텍스트에 저장 (1차 캐시)
  → 동시에 "스냅샷"도 저장 (이 시점의 필드값 복사본)

트랜잭션 커밋 시:
  → flush() 호출
  → 영속성 컨텍스트의 모든 Entity를 순회
  → 각 Entity의 현재값 vs 스냅샷 비교
  → 다른 필드가 있으면 UPDATE SQL 생성 및 실행
```

### 쓰기 지연 (Write-Behind)

```java
@Transactional
public void createMultiple() {
    repo.save(vehicle1);   // INSERT SQL이 바로 실행되지 않음!
    repo.save(vehicle2);   // 이것도 아직 안 됨
    repo.save(vehicle3);   // 이것도
    // ... 다른 로직 ...
}
// 트랜잭션 커밋 시점에 3개의 INSERT가 한번에 flush
```

**왜?** DB와의 통신 횟수를 줄이기 위해서. 중간에 예외가 발생하면 3개 전부 rollback된다.

### Proxy와 지연 로딩 (Lazy Loading)

```java
@Entity
public class Vehicle {
    @ManyToOne(fetch = FetchType.LAZY)
    private Shipper shipper;  // 실제로는 Proxy 객체
}

Vehicle v = vehicleRepository.findById(1);
// 이 시점에 shipper는 Proxy 객체 (DB 조회 안 함)

v.getShipper().getName();
// getName()을 호출하는 순간 → Proxy가 DB 조회 실행
// SELECT * FROM shippers WHERE id = ?
```

Hibernate는 CGLIB으로 Entity의 **프록시 서브클래스**를 만든다:

```java
// Hibernate가 내부적으로 생성 (개념적)
class Shipper$$HibernateProxy extends Shipper {
    private boolean initialized = false;
    private Shipper target;

    @Override
    public String getName() {
        if (!initialized) {
            target = loadFromDatabase();  // 이때 SELECT 실행!
            initialized = true;
        }
        return target.getName();
    }
}
```

### N+1 문제

```java
List<Vehicle> vehicles = vehicleRepo.findAll();  // 1번 쿼리: SELECT * FROM vehicles
for (Vehicle v : vehicles) {
    v.getShipper().getName();  // 각 차량마다 1번씩: SELECT * FROM shippers WHERE id=?
}
// 차량 100대 → 총 101번 쿼리 (1 + 100)
```

해결책: `@EntityGraph`, `fetch join`, `@BatchSize` 등

---

## 5-2. Hibernate Filter — 자동 WHERE 조건 추가

### 이 프로젝트의 멀티테넌시 핵심

```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "companyId", type = Long.class))
@Filter(name = "tenantFilter", condition = "company_id = :companyId")
public abstract class TenantEntity extends BaseEntity { ... }
```

**Hibernate Filter가 하는 일**: 활성화되면, 이 Entity를 대상으로 하는 **모든 SELECT 쿼리**에 자동으로 `WHERE company_id = ?`를 추가한다.

```sql
-- Filter 비활성화 상태
SELECT * FROM vehicles

-- Filter 활성화 상태 (companyId = 1)
SELECT * FROM vehicles WHERE company_id = 1
-- ↑ 개발자가 WHERE 조건을 안 써도 자동으로 붙음!
```

**JPA Specification과의 차이**: Specification은 개발자가 의식적으로 조건을 추가해야 하지만, Hibernate Filter는 활성화만 되면 모든 쿼리에 자동 적용된다. 실수로 테넌트 조건을 빠뜨릴 수 없다.

---

## 5-3. @Transactional의 내부 동작

### 트랜잭션의 ACID 속성

```
Atomicity (원자성): 전부 성공하거나 전부 실패. 중간 상태 없음.
Consistency (일관성): 트랜잭션 전후에 DB가 일관된 상태 유지.
Isolation (격리성): 동시에 실행되는 트랜잭션이 서로 간섭하지 않음.
Durability (지속성): 커밋된 데이터는 시스템 장애가 발생해도 유지.
```

### Spring의 @Transactional AOP 동작

```java
// 개발자가 쓰는 코드
@Transactional
public void createVehicle(VehicleCreateRequest request) {
    Vehicle v = new Vehicle();
    vehicleRepository.save(v);
    documentRepository.save(doc);
}

// Spring AOP가 실제로 실행하는 코드 (개념적)
public void createVehicle_proxy(VehicleCreateRequest request) {
    TransactionStatus tx = transactionManager.getTransaction();  // BEGIN
    try {
        target.createVehicle(request);  // 원본 메서드 실행
        transactionManager.commit(tx);   // COMMIT
    } catch (RuntimeException e) {
        transactionManager.rollback(tx); // ROLLBACK
        throw e;
    }
}
```

### readOnly = true의 의미

```java
@Transactional(readOnly = true)
public List<Vehicle> getVehicles() { ... }
```

1. Hibernate가 **Dirty Checking을 하지 않음** → 스냅샷을 안 만들어서 메모리 절약
2. DB에 따라 **읽기 전용 트랜잭션 최적화** 적용 (MySQL: 트랜잭션 ID 미발급)
3. flush가 발생하지 않음 → 실수로 save()를 호출해도 DB에 반영 안 됨

---

# Part 6. Redis 내부 구조

---

## 6-1. Redis가 빠른 이유

### 싱글 스레드 + 메모리 기반

```
일반 DB (MySQL):
  요청 → 디스크에서 데이터 읽기 (수 ms) → 응답
  동시 요청 → 멀티 스레드 → 락 경쟁 → 컨텍스트 스위칭

Redis:
  요청 → 메모리에서 데이터 읽기 (수 μs) → 응답
  동시 요청 → 싱글 스레드 이벤트 루프 → 순차 처리 → 락 불필요
```

**싱글 스레드인데 어떻게 빠른가?**

1. **메모리 접근은 나노초 단위**: 디스크 I/O(밀리초)보다 1000배 이상 빠름
2. **I/O 멀티플렉싱(epoll)**: 하나의 스레드가 수만 개의 소켓을 동시에 감시
3. **락이 필요 없음**: 싱글 스레드라서 데이터 경쟁(race condition)이 원천 차단

```
[epoll 이벤트 루프]
while (true) {
    events = epoll_wait(소켓_목록);   // "이 소켓들 중 데이터가 온 것은?"
    for (event : events) {
        command = read(event.socket);
        result = execute(command);     // 명령 처리 (메모리 연산, 매우 빠름)
        write(event.socket, result);
    }
}
```

### Redis 데이터 구조와 이 프로젝트에서의 활용

```
String (문자열):
  SET auth:login:fail:user:hong "3"        ← 로그인 실패 횟수
  SET auth:login:lock:user:hong "1" EX 900 ← 계정 잠금 (15분 TTL)
  SET ocr:jobs:queued:42 "1" EX 21600      ← 중복 enqueue 방지 마커

List (리스트 — 이중 연결 리스트):
  LPUSH ocr:jobs:queue "42"                ← 작업 큐에 추가 (왼쪽)
  BRPOP ocr:jobs:queue 2                   ← 작업 꺼내기 (오른쪽, 블로킹)
  → FIFO 큐로 동작!

Hash (해시맵):
  HSET rt:user:1 "abc123hash" "1"          ← Refresh Token 저장
  HDEL rt:user:1 "abc123hash"              ← 특정 Token 폐기
  DEL rt:user:1                            ← 유저의 모든 Token 폐기 (로그아웃)
```

### BRPOP — 블로킹 큐의 원리

```
RPOP: 리스트가 비어있으면 null 반환 (즉시)
BRPOP: 리스트가 비어있으면 데이터가 올 때까지 대기 (블로킹)

BRPOP ocr:jobs:queue 2
→ "2초 동안 기다릴게. 그 안에 데이터가 들어오면 바로 반환해줘"
```

이것이 Redis List를 **메시지 큐**로 쓸 수 있는 핵심이다. Polling(주기적 확인) 대비 장점:

```
Polling:
  while(true) {
    result = RPOP queue;
    if (result == null) sleep(100ms);  // CPU 낭비 + 100ms 지연
  }

BRPOP:
  while(true) {
    result = BRPOP queue 2;  // 데이터 오면 즉시 반환, CPU 낭비 없음
  }
```

### SETNX (SET if Not eXists) — 원자적 중복 방지

```java
// 이 프로젝트의 OcrQueueService
Boolean reserved = redis.opsForValue().setIfAbsent(markerKey, "1", Duration.ofHours(6));
```

```
SETNX ocr:jobs:queued:42 "1"
→ 키가 없으면: 생성하고 true 반환
→ 키가 있으면: 아무것도 안 하고 false 반환

이것이 원자적(atomic)으로 실행됨:
싱글 스레드이므로 두 스레드가 동시에 SETNX를 해도 하나만 성공
```

### TTL (Time-To-Live) — 자동 만료

```
SET auth:login:fail:user:hong "1" EX 600  ← 600초(10분) 후 자동 삭제

Redis 내부:
  - 각 키마다 만료 시각을 별도 딕셔너리에 저장
  - 키에 접근할 때(passive) 만료 여부 확인 → 만료됐으면 삭제
  - 100ms마다 랜덤으로 20개 키를 뽑아서(active) 만료 여부 확인
  - 이 두 전략을 조합하여 메모리를 효율적으로 관리
```

---

# Part 7. Java 동시성 (Concurrency)

---

## 7-1. ThreadLocal — 스레드별 독립 저장소

### 내부 구조

```java
public class ThreadLocal<T> {
    // 실제로는 각 Thread 객체 안에 ThreadLocalMap이 있다:
    // Thread.threadLocals = ThreadLocalMap

    public T get() {
        Thread t = Thread.currentThread();        // 현재 스레드 가져오기
        ThreadLocalMap map = t.threadLocals;       // 스레드의 맵 가져오기
        return map.get(this);                      // 이 ThreadLocal을 키로 값 조회
    }

    public void set(T value) {
        Thread t = Thread.currentThread();
        ThreadLocalMap map = t.threadLocals;
        map.set(this, value);                      // 이 ThreadLocal을 키로 값 저장
    }
}
```

**핵심**: 값이 `ThreadLocal` 객체 안에 저장되는 게 아니라, **각 Thread 객체 안**에 저장된다. ThreadLocal은 단지 "키" 역할을 한다.

```
Thread-1의 threadLocals 맵:
  TenantContext.COMPANY_ID → 1
  SecurityContext → {user: "hong"}

Thread-2의 threadLocals 맵:
  TenantContext.COMPANY_ID → 2
  SecurityContext → {user: "kim"}
```

### 메모리 누수 주의

스레드 풀의 스레드는 재사용된다. `clear()`를 하지 않으면:

```
요청1 (회사A, 스레드1): TenantContext.setCompanyId(1)
요청1 처리 완료 → 스레드1 반환

요청2 (인증 실패, 스레드1 재사용): TenantContext에 여전히 companyId=1이 남아있음!
→ 인증 안 된 사용자가 회사A의 데이터에 접근할 수 있음
```

그래서 이 프로젝트의 JWT 필터에 `finally { TenantContext.clear(); }`가 반드시 있다.

---

## 7-2. Semaphore — 동시 접근 제한

### 운영체제 수준의 개념

Semaphore는 **공유 자원에 동시 접근할 수 있는 스레드 수를 제한**하는 동기화 도구다.

```
Semaphore(3) → 허가증(permit) 3장

스레드1: acquire() → permit 1개 사용 (남은: 2)
스레드2: acquire() → permit 1개 사용 (남은: 1)
스레드3: acquire() → permit 1개 사용 (남은: 0)
스레드4: acquire() → 대기 (permit 없음)
                        ↓
스레드1: release() → permit 반환 (남은: 1)
스레드4: 대기 해제 → permit 사용 (남은: 0)
```

### Java Semaphore의 내부

Java의 `Semaphore`는 `AbstractQueuedSynchronizer(AQS)`를 기반으로 한다:

```
AQS 내부:
  state = 사용 가능한 permit 수
  waitQueue = 대기 중인 스레드들의 큐 (FIFO)

acquire():
  CAS로 state를 1 감소 시도
  성공하면 → 진행
  실패하면 (state == 0) → waitQueue에 들어가서 park() (스레드 일시정지)

release():
  state를 1 증가
  waitQueue에서 하나 꺼내서 unpark() (스레드 깨우기)
```

**fair=true (공정 모드)**: 이 프로젝트의 `new Semaphore(globalPermits, true)`에서 `true`는 공정 모드다. 먼저 대기한 스레드가 먼저 permit을 받는다. `false`면 새로 온 스레드가 먼저 끼어들 수 있다.

### 이 프로젝트에서의 이중 Semaphore

```java
// UpstageCallGuard
public <T> T run(Long companyId, Supplier<T> action) {
    g = global.tryAcquire(60, SECONDS);    // 전역: 동시 1건
    c = company.tryAcquire(60, SECONDS);    // 회사별: 동시 1건

    return action.get();
}
```

```
[전역 Semaphore (permits=1)]
  → Upstage API가 초당 1건만 받으므로, 전체 시스템에서 동시 1건만 호출

[회사별 Semaphore (permits=1)]
  → 특정 회사가 모든 permit을 독점하는 것을 방지
  → 공정한 API 사용 보장
```

---

## 7-3. CAS (Compare-And-Swap) — Lock-free 동시성

### AtomicInteger의 원리

```java
// 이 프로젝트의 OcrJobWorker
private final AtomicInteger inflightTotal = new AtomicInteger(0);
inflightTotal.incrementAndGet();
inflightTotal.decrementAndGet();
```

일반 `count++`는 스레드-안전하지 않다:

```
count++은 실제로 3단계:
1. 메모리에서 count 읽기 (READ)
2. 값에 1 더하기 (ADD)
3. 메모리에 쓰기 (WRITE)

스레드1: READ count=5 → ADD → count=6 → WRITE count=6
스레드2: READ count=5 → ADD → count=6 → WRITE count=6
결과: count=6 (7이어야 하는데!)
```

**CAS 연산**:

```
CAS(메모리주소, 기대값, 새값):
  if (메모리주소의 값 == 기대값) {
      메모리주소의 값 = 새값;
      return true;  // 성공
  } else {
      return false; // 실패 (다른 스레드가 먼저 바꿈)
  }
  // 이 전체가 CPU 명령어 하나로 원자적 실행! (x86: CMPXCHG 명령어)
```

```
AtomicInteger.incrementAndGet() 내부:
  while (true) {
      int current = get();              // 현재값 읽기 (5)
      int next = current + 1;           // 새값 계산 (6)
      if (compareAndSwap(current, next)) {
          return next;                  // 성공! → 6 반환
      }
      // 실패 → 다른 스레드가 먼저 바꿈 → 다시 시도 (spin)
  }
```

**Lock vs CAS**:
- Lock: 스레드를 재우고(park) 깨우는(unpark) 비용이 큼 (OS 커널 호출)
- CAS: CPU 명령어 수준에서 처리, 경쟁이 심하지 않으면 훨씬 빠름
- CAS 단점: 경쟁이 극심하면 spin이 계속 돌아서 CPU 낭비

---

## 7-4. ConcurrentHashMap — 세분화된 락

### 왜 HashMap은 스레드-안전하지 않은가?

HashMap 내부는 배열 + 연결 리스트(또는 트리)다:

```
buckets[0] → null
buckets[1] → Entry("key1", val) → Entry("key5", val)
buckets[2] → null
buckets[3] → Entry("key2", val)
...

put("key6", val):
  1. hash("key6") % buckets.length → index 계산
  2. buckets[index]에 Entry 추가

두 스레드가 같은 bucket에 동시에 put하면 → 연결 리스트가 꼬임 → 무한루프 가능!
```

### ConcurrentHashMap의 해결책

Java 8+의 ConcurrentHashMap은 **버킷 단위 락(Node-level synchronization)**을 사용한다:

```
buckets[0] → [synchronized] Entry → Entry
buckets[1] → [synchronized] Entry → Entry
buckets[2] → [synchronized] Entry

스레드1이 buckets[0]에 접근 → buckets[0]만 잠금
스레드2가 buckets[1]에 접근 → buckets[1]만 잠금 (동시 진행 가능!)
스레드3가 buckets[0]에 접근 → 스레드1이 끝날 때까지 대기
```

전체 Map을 잠그는 게 아니라 **해당 버킷만** 잠그므로, 대부분의 동시 접근이 서로 간섭하지 않는다.

### CopyOnWriteArrayList (SSE에서 사용)

```java
// SseHub에서
private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters;
```

**Copy-On-Write 전략**:

```
읽기 (반복, 접근): 내부 배열을 그대로 참조 → 락 없음 → 매우 빠름
쓰기 (추가, 삭제): 내부 배열 전체를 복사하여 새 배열을 만들고 교체 → 느림

적합한 상황: 읽기가 99%, 쓰기가 1%인 경우
→ SSE emitter 목록: heartbeat(30초마다 모든 emitter 순회 = 읽기)가 빈번,
   연결/해제(쓰기)는 드묾 → 딱 맞음
```

---

## 7-5. Executor와 스레드 풀

### ExecutorService의 내부

```java
// 이 프로젝트의 OcrJobWorker
processingPool = Executors.newFixedThreadPool(effectiveConcurrency, r -> {
    Thread t = new Thread(r, "ocr-worker-" + workerIdx.incrementAndGet());
    t.setDaemon(true);
    return t;
});
```

`newFixedThreadPool(N)` 내부:

```
┌────────────────────────────────────────────┐
│  ThreadPoolExecutor                        │
│                                            │
│  corePoolSize = N    (항상 유지하는 스레드)    │
│  maxPoolSize  = N    (최대 스레드)            │
│  keepAliveTime = 0   (초과 스레드 즉시 종료)   │
│                                            │
│  workQueue = LinkedBlockingQueue (무제한)    │
│  ┌──────────────────────────────────┐      │
│  │  [task1] [task2] [task3] ...     │      │
│  └──────────────────────────────────┘      │
│                                            │
│  workers:                                  │
│  [ocr-worker-1] → 큐에서 꺼내서 실행         │
│  [ocr-worker-2] → 큐에서 꺼내서 실행         │
│  ...                                       │
└────────────────────────────────────────────┘
```

**Daemon Thread란?** `t.setDaemon(true)` — JVM이 종료될 때 이 스레드가 실행 중이어도 강제 종료한다. 일반 스레드(non-daemon)가 모두 종료되면 JVM이 종료되는데, daemon 스레드는 이를 막지 않는다. 백그라운드 워커에 적합하다.

---

# Part 8. 인프라와 보안

---

## 8-1. Docker 컨테이너의 원리

### 컨테이너 vs 가상머신

```
가상머신 (VM):
  [App] [App]     [App] [App]
  [Guest OS]      [Guest OS]       ← OS 전체를 각각 실행 (수 GB)
  ─────────────────────────
  [Hypervisor (VMware, VirtualBox)]
  [Host OS]
  [Hardware]

컨테이너 (Docker):
  [App] [App]     [App] [App]
  ─────────────────────────
  [Docker Engine]                  ← OS 커널 공유 (수십 MB)
  [Host OS (Linux Kernel)]
  [Hardware]
```

컨테이너는 OS 커널을 공유하되, **Linux의 namespace와 cgroup**으로 격리한다:

```
namespace: "각 컨테이너가 자신만의 파일시스템, 네트워크, 프로세스 목록을 가짐"
  - PID namespace: 컨테이너 안에서 PID 1번이 앱 프로세스
  - NET namespace: 컨테이너마다 독립적인 IP, 포트
  - MNT namespace: 컨테이너마다 독립적인 파일시스템

cgroup: "CPU, 메모리, I/O 사용량 제한"
  - 컨테이너에 메모리 512MB만 할당
  - CPU 50%만 사용하도록 제한
```

### docker-compose.yml의 의미

```yaml
services:
  mysql:
    image: mysql:8.0            # Docker Hub에서 MySQL 이미지 다운로드
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping"]
      interval: 10s             # 10초마다 건강 상태 확인

  app:
    build: .                    # 현재 디렉토리의 Dockerfile로 이미지 빌드
    depends_on:
      mysql:
        condition: service_healthy  # MySQL이 healthy 상태가 될 때까지 대기
    environment:
      DATABASE_URL: jdbc:mysql://mysql:3306/cafe
      #                          ↑ "mysql"은 Docker 내부 DNS로 해석됨
```

**Docker 내부 네트워크**: `docker-compose`의 서비스들은 같은 가상 네트워크에 속한다. `mysql`이라는 호스트명으로 MySQL 컨테이너의 IP를 자동으로 찾는다 (내장 DNS).

---

## 8-2. CORS — 브라우저 보안 모델

### Same-Origin Policy (동일 출처 정책)

브라우저의 기본 보안 정책: **출처(Origin)가 다른 서버에 요청을 보내지 못한다.**

```
출처(Origin) = 프로토콜 + 호스트 + 포트

http://localhost:5173 (프론트엔드)  ≠  http://localhost:8080 (백엔드)
            ↑ 포트가 다르므로 "다른 출처"
```

### CORS Preflight 요청

브라우저가 다른 출처에 실제 요청을 보내기 전에, **"이 요청을 보내도 돼?"**라고 먼저 물어본다:

```
① Preflight (브라우저가 자동으로 보냄)
OPTIONS /api/vehicles HTTP/1.1
Origin: http://localhost:5173
Access-Control-Request-Method: POST
Access-Control-Request-Headers: Content-Type, Authorization

② 서버 응답
HTTP/1.1 200 OK
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET, POST, PUT, DELETE
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 3600        ← 1시간 동안 preflight 캐시

③ 실제 요청 (preflight이 OK면)
POST /api/vehicles HTTP/1.1
Origin: http://localhost:5173
Authorization: Bearer eyJ...
```

`Access-Control-Max-Age: 3600`이 이 프로젝트의 `config.setMaxAge(3600L)`에 해당한다. 1시간 동안 같은 요청에 대해 preflight를 다시 보내지 않아서 성능이 개선된다.

### CSRF (Cross-Site Request Forgery)를 disable한 이유

```java
http.csrf(AbstractHttpConfigurer::disable)
```

CSRF 공격: 악성 사이트가 사용자 브라우저를 이용해 서버에 요청을 보냄 (쿠키가 자동 전송되므로).

**세션 기반 인증에서는 위험**: 쿠키에 세션ID가 있으므로, 다른 사이트에서도 요청하면 세션ID가 함께 가서 인증됨.

**JWT 기반에서는 안전**: Access Token이 `Authorization` 헤더에 있고, 이건 JavaScript로 명시적으로 넣어줘야 함. 다른 사이트에서 자동으로 넣을 수 없음. 그래서 CSRF 보호를 꺼도 된다.

단, Refresh Token은 쿠키에 있으므로 CSRF에 취약할 수 있다. 이를 `SameSite=Lax`로 방어한다 — 다른 사이트에서 오는 POST 요청에는 쿠키를 보내지 않는다.

---

## 8-3. MDC (Mapped Diagnostic Context) — 구조화된 로깅

### 왜 필요한가?

서버에 동시에 100개 요청이 들어오면, 로그가 뒤섞인다:

```
10:00:00.001 INFO  차량 조회 시작
10:00:00.002 INFO  사용자 인증 성공
10:00:00.003 ERROR 차량 조회 실패 - 404
10:00:00.003 INFO  차량 조회 시작
```

어떤 로그가 어떤 요청의 것인지 알 수 없다.

### MDC의 내부 — ThreadLocal 기반

```java
// SLF4J MDC 내부 (간략화)
public class MDC {
    private static final ThreadLocal<Map<String, String>> context = new ThreadLocal<>();

    public static void put(String key, String val) {
        getMap().put(key, val);
    }

    public static String get(String key) {
        return getMap().get(key);
    }
}
```

MDC도 **ThreadLocal**이다! 각 요청(스레드)마다 독립적인 컨텍스트 정보를 가진다.

로그 패턴에 `%X{requestId}`를 넣으면:

```
10:00:00.001 [req=a3f8] [company=1] [user=hong] INFO  차량 조회 시작
10:00:00.002 [req=b7c2] [company=2] [user=kim]  INFO  사용자 인증 성공
10:00:00.003 [req=a3f8] [company=1] [user=hong] ERROR 차량 조회 실패 - 404
```

이제 `req=a3f8`로 grep하면 하나의 요청에 대한 로그만 추출할 수 있다.

---

## 8-4. 지수 백오프 (Exponential Backoff)

### 분산 시스템의 필수 패턴

서버가 일시적으로 장애가 나면, 클라이언트가 즉시 재시도한다. 수천 개의 클라이언트가 동시에 재시도하면 → **Thundering Herd (떼몰이)** 현상 → 서버가 더 죽는다.

```
고정 간격 재시도:    1초 → 1초 → 1초 → 1초 (계속 1초마다)
                   → 서버가 회복할 틈이 없음

지수 백오프:         1초 → 2초 → 4초 → 8초 → 16초 → ... → 60초(최대)
                   → 점점 간격이 넓어져서 서버에 여유를 줌
```

이 프로젝트의 구현:

```java
// OcrJobWorker.pollLoop()
long backoffMs = 1_000;           // 초기: 1초
// 에러 발생 시:
sleepQuietly(backoffMs);
backoffMs = Math.min(backoffMs * 2, 60_000);  // 2배씩 증가, 최대 60초
// 성공 시:
backoffMs = 1_000;                // 초기화
```

### Stale Job 복구의 백오프

```java
private long computeBackoffSeconds(Long attempt) {
    // attempt=1 → 30초
    // attempt=2 → 60초
    // attempt=3 → 120초
    // ...
    // 최대 1800초 (30분)
}
```

이렇게 하면 영구적으로 실패하는 작업이 Redis와 DB에 무한 부하를 주는 것을 방지한다.

---

## 8-5. DLQ (Dead Letter Queue) — 처리 불가 메시지 격리

### 메시지 큐에서 처리할 수 없는 메시지가 생기면?

```
정상: queue → worker → 처리 성공 → 완료
비정상: queue → worker → 처리 실패 → 다시 queue에 → 또 실패 → 또 queue에 → ∞

이걸 방치하면 "독이 든 메시지(Poison Message)"가 큐를 영원히 점유한다.
```

DLQ는 **처리할 수 없는 메시지를 별도 공간에 격리**한다:

```java
// 이 프로젝트의 OcrQueueService
public void enqueueDlq(String payload, String reason) {
    String item = reason + "|" + payload;     // "INVALID_JOB_ID|abc"
    redis.opsForList().leftPush(DLQ_KEY, item);
    redis.opsForList().trim(DLQ_KEY, 0, 999);  // 최대 1000개만 보관
}
```

DLQ에 쌓인 메시지는 운영자가 나중에 확인하고 원인을 분석한다.

---

## 8-6. fail-open vs fail-closed

### 보조 시스템 장애 시 어떻게 할 것인가?

```
fail-closed: 보조 시스템이 죽으면 → 전체 기능 중단
  "Redis가 죽었으니 로그인도 안 됩니다" → 서비스 전체 장애

fail-open: 보조 시스템이 죽으면 → 보조 기능만 건너뛰고 핵심 기능은 유지
  "Redis가 죽었으니 rate limiting은 못 하지만, 로그인은 됩니다"
```

이 프로젝트의 선택:

```java
// LoginSecurityService
public void assertAllowed(String loginId, String clientIp) {
    try {
        // Redis에서 잠금 상태 확인
    } catch (Exception e) {
        // Redis 장애 시 로그만 남기고 통과 (fail-open)
        log.error("[LoginSecurity] check failed (fail-open)");
        // → throw하지 않음! 로그인 자체는 허용
    }
}
```

**판단 기준**: "이 기능이 없으면 서비스가 완전히 멈춰야 하는가?"

```
fail-closed가 맞는 경우: DB 연결 장애 → 데이터 없이 서비스 불가
fail-open이 맞는 경우: Rate Limiting 장애 → 잠시 보안이 약해지지만 서비스는 가능
```

---

> **마치며**: 이 문서에서 다룬 개념들은 독립적으로 존재하는 게 아니라 서로 얽혀 있다. ThreadLocal이 TenantContext를 만들고, AOP가 ThreadLocal을 읽어서 Hibernate Filter를 활성화하고, 그 Filter가 JPA 쿼리에 WHERE 조건을 추가한다. 하나의 개념을 깊이 이해하면 다른 개념이 왜 그 자리에 있는지 자연스럽게 보이기 시작한다.
