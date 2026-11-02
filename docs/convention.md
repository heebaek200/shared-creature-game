# 코드 컨벤션

이 문서는 팀원들이 **일관된 방식으로 코드를 작성하고, 서로의 코드를 쉽게 이해하고 수정할 수 있도록** 작성되었습니다.

처음부터 모든 규칙을 외울 필요는 없습니다. 개발하다가 구현 방법이 고민될 때 해당 항목을 찾아보세요.

코드 컨벤션은 팀원을 평가하거나 실수를 지적하기 위한 문서가 아닙니다. 서로 다른 개발 습관 때문에 발생하는 혼란을 줄이기 위한 공통 약속입니다.

# 공통 규칙

## 코드 문서화와 주석

### 언어별 표준 문서화 주석 작성 (필수)

팀이 직접 작성한 주요 클래스, 모듈, 메서드와 함수에는 해당 언어의 표준 문서화 형식으로 주석을 작성합니다.

| 언어 | 문서화 형식 |
|---|---|
| Java | Javadoc |
| JavaScript·TypeScript | JSDoc |
| Python | docstring |
| CSS | 구역과 공통 스타일의 목적을 설명하는 블록 주석 |
| HTML | 구조나 공통 컴포넌트의 의도를 설명하는 HTML 주석 |

문서화 주석에는 다음 내용을 필요한 범위에서 작성합니다.

- 코드의 역할과 작성 목적
- 주요 처리 과정과 업무 규칙
- 입력값과 반환값
- 호출자가 알아야 하는 예외와 부작용
- 사용 시 주의사항

Java에서 입력값, 반환값이나 호출자가 알아야 하는 예외가 있다면 `@param`, `@return`, `@throws`를 작성합니다.

자동 생성 코드, 단순 Getter·Setter, 의미가 자명한 위임 메서드와 Override는 문서화 대상에서 제외할 수 있습니다.

### 내부 로직 주석 작성 (권장)

복잡한 조건, 업무 규칙, 우회 처리와 외부 시스템 연동처럼 코드만으로 의도를 이해하기 어려운 부분에는 인라인 주석을 작성합니다.

주석은 코드가 무엇을 하는지 반복하지 않고, **해당 방식으로 구현한 이유와 주의할 점**을 설명합니다.

좋은 예:

```java
// 삭제된 회원의 이메일은 재가입에 사용할 수 없으므로
// 탈퇴 여부와 관계없이 중복 여부를 확인합니다.
```

불필요한 예:

```java
// 회원을 조회합니다.
User user = userRepository.findById(id).orElseThrow();
```

코드를 변경하여 주석과 구현이 달라지면 같은 작업에서 함께 수정합니다.

# 1. 백엔드 (Spring Boot)

## 1.0. Java 실행 환경

- Java 21 LTS를 로컬 개발, Gradle Toolchain과 CI의 공통 기준으로 사용합니다.
- Gradle은 Java Toolchain 21을 선언하고 팀원은 JDK 21로 빌드·테스트합니다.
- 기준 Package는 `com.team.sharedcreature`입니다. 새 백엔드 코드는 이 Root Package 아래에 작성합니다.

## 1.1. 로깅

**프로그램의 실행 상태를 확인할 때는 `System.out.println()` 대신 로거를 사용합니다.**

로깅은 SLF4J를 기준으로 작성하며, 프로젝트에서 Lombok을 사용하는 경우 `@Slf4j`를 활용해도 좋습니다.

| 레벨 | 사용 목적 | 사용 빈도 |
|---|---|---|
| DEBUG | 개발 중 실행 흐름, 변수값, 조회 결과 등을 확인 | 개발 중 자유롭게 사용 |
| INFO | 주요 작업의 정상적인 완료, 시스템 상태 변화 | 중요한 이벤트에만 사용 |
| WARN | 요청 거절, 예상 가능한 비정상 상황 중 확인이 필요한 경우 | 필요한 경우 사용 |
| ERROR | 예상하지 못한 오류, 정상적인 처리가 불가능한 장애 | 실제 오류 발생 시 사용 |

### 로그 작성 규칙

- 임시 디버깅 로그는 자유롭게 작성하되 PR 전에 제거해주세요.
- 장애 원인 분석에 지속적으로 도움이 되는 DEBUG 로그는 남겨도 됩니다.
- 문자열을 `+`로 연결하지 않고 SLF4J의 `{}` 자리표시자를 사용합니다.
- 운영 환경(prod)에서는 일반적으로 DEBUG 로그 출력을 비활성하게 됩니다.
- 비밀번호, 인증 토큰, 세션 ID, 이메일, 전화번호, 요청 본문 등 민감한 정보와 개인정보는 로그에 기록하지 않습니다.
- 예외 처리는 중앙에서 담당하고, 동일한 오류의 로그를 여러 계층에서 중복으로 기록하지 않습니다.

## 1.2. 패키지 구조

프로젝트의 패키지는 도메인(기능) 단위로 구성합니다.

Controller, Service, Repository를 각각 하나의 공통 패키지에 모으기보다, 동일한 기능의 코드를 같은 패키지에 배치합니다.

예시:

```text
com.example.project
├── _core
│   ├── config
│   ├── error
│   ├── interceptor
│   └── util
│
├── user
│   ├── User.java
│   ├── UserController.java
│   ├── UserApiController.java
│   ├── UserService.java
│   ├── UserRepository.java
│   ├── UserRequest.java
│   └── UserResponse.java
│
├── board
│   ├── Board.java
│   ├── BoardController.java
│   ├── BoardApiController.java
│   ├── BoardService.java
│   ├── BoardRepository.java
│   ├── BoardRequest.java
│   └── BoardResponse.java
│
└── comment
    └── ...
```

### 공통 기능은 `_core`에 배치합니다.

`_core`에는 여러 도메인에서 공통으로 사용하는 기술적인 코드를 배치합니다.

- `config`: Spring 설정
- `error`: 공통 예외 및 예외 처리기
- `interceptor`: 요청 처리 공통 기능
- `util`: 범용 유틸리티

**특정 도메인에서만 사용하는 코드는 `_core`에 넣지 않습니다.**

예를 들어 회원 등급을 계산하는 로직은 `user` 도메인에 두는 것이 적절합니다.

프로젝트가 커진다면 도메인 내부에 `dto`, `controller` 등의 하위 패키지를 만들어도 됩니다. 다만 파일 수가 적은 초기 단계에서는 불필요하게 패키지를 세분화하지 않습니다.

### 프로젝트 적용 구조

```text
com.team.sharedcreature
├── _core
│   ├── api
│   ├── config
│   ├── error
│   ├── security
│   ├── time
│   ├── concurrency
│   └── websocket
├── auth
├── user
├── group
├── season
├── quest
├── ranking
├── avatar
├── payment
└── admin
```

- 파일이 적은 기능은 도메인 패키지 안에서 단순 구조를 유지합니다.
- 그룹, 퀘스트, 결제처럼 파일과 책임이 많은 기능은 `api`, `application`, `domain`, `infrastructure`로 나눕니다.
- `_core`에는 여러 기능이 공유하는 기술 코드만 두고 그룹·퀘스트·결제 업무 규칙을 넣지 않습니다.
- `admin`은 여러 도메인의 관리 조회와 명령을 조정하지만 다른 도메인의 Entity를 소유하지 않습니다.
- 포괄적인 단일 `GroupService`가 커지면 `GroupMembershipService`, `GroupAdminService`, `GroupInvitationService`처럼 업무 책임으로 분리합니다.

## 1.3. 계층 구조

**Controller, Service, Repository는 서로 다른 책임을 담당합니다.**

기본적인 처리 흐름은 다음과 같습니다.

```text
Client
   ↓
Controller
   ↓
Service
   ↓
Repository
   ↓
Database
```

프로젝트의 확장 구조에서는 `API → Application → Domain`을 기본 방향으로 사용합니다. Application은 Repository와 외부 Port를 호출하고 Infrastructure는 JPA Query와 외부 시스템 Adapter를 구현합니다. 단순 CRUD마다 Interface를 만들지 않고 결제 공급자처럼 실제 교체·격리가 필요한 경계에 Port Interface를 둡니다.

### 클래스 명명 규칙

| 역할 | 규칙 | 예시 |
|---|---|---|
| Entity | 도메인명 | `Board` |
| API Controller | 도메인명 + ApiController | `BoardApiController` |
| Service | 도메인명 + Service | `BoardService` |
| Repository | 도메인명 + Repository | `BoardRepository` |
| 요청 DTO | 도메인명 + Request | `BoardRequest` |
| 응답 DTO | 도메인명 + Response | `BoardResponse` |

DTO 수가 적은 경우에는 `BoardRequest.CreateDto`, `BoardRequest.UpdateDto`와 같은 내부 정적 클래스로 정리할 수 있습니다. 도메인의 규모가 커지면 기능에 따라 `BoardCreateRequest`, `BoardUpdateRequest`처럼 클래스를 나누는 방식도 있습니다.

### Controller

**Controller는 HTTP 요청을 받아 Service에 전달하고, 처리 결과를 응답하는 역할입니다.**

Controller가 담당하는 작업:

- URL과 HTTP Method 매핑
- 요청값 수신 및 형식 검증
- 인증된 사용자 정보 전달
- 트랜잭션 외부에서 진행할 입력값 검증(validation)
- Service 호출
- JSON 응답 DTO 또는 Redirect 응답 반환

Controller에서 하지 않을 작업:

- Repository 직접 호출
- 업무 규칙 및 복잡한 계산 처리
- 트랜잭션 관리
- Entity를 요청이나 응답으로 직접 사용

백엔드는 Full CSR 클라이언트에 REST API를 제공하며 서버에서 업무 화면 HTML을 렌더링하지 않습니다. OAuth 로그인 시작·콜백처럼 Redirect가 필요한 인증 엔드포인트는 API Controller에서 명시적으로 구분합니다.

### Service

**Service는 실제 업무 규칙을 처리하는 중심 계층입니다.**

예를 들어 다음과 같은 규칙은 Service가 담당합니다.

- 회원은 자신의 게시글만 수정할 수 있습니다.
- 재고가 부족하면 주문할 수 없습니다.
- 이미 가입된 이메일은 다시 사용할 수 없습니다.

Service에서는 필요한 Repository를 호출하고, 트랜잭션 범위를 관리합니다.

HTTP 요청 객체인 `HttpServletRequest`, `HttpSession` 등에 의존하지 않도록 작성해주세요. 업무에 필요한 사용자 ID 등의 값은 Controller에서 전달하는 방식을 권장합니다.

### Repository

**Repository는 데이터베이스 접근을 담당합니다.**

- Entity 조회, 저장, 삭제
- 조건 검색
- 정렬, 페이징, 집계
- 필요한 경우 사용자 정의 쿼리 작성

Repository에 업무 규칙을 작성하지 않습니다.

예를 들어 `findByEmail()`은 Repository가 담당하지만, 해당 이메일로 가입할 수 있는지를 최종 판단하는 작업은 Service가 담당합니다.

### Entity

**Entity는 데이터베이스에 저장되는 데이터와 해당 데이터의 상태를 관리합니다.**

Entity에는 데이터뿐만 아니라 자신과 밀접한 상태 변경 로직을 포함할 수 있습니다.

예를 들어 게시글 제목 변경은 다음과 같은 메서드로 표현할 수 있습니다.

Entity는 Setter 메서드를 작성하지 않는 것을 원칙으로 합니다.

```java
board.updateTitle(newTitle);
```

모든 필드에 setter를 공개하기보다는 의미 있는 상태 변경 메서드를 만드는 방식을 권장합니다.

단, Entity에 HTTP 요청 처리나 외부 API 호출 등의 역할을 추가하지 않습니다.

## 1.4. DTO와 입력값 검증

**외부에서 받는 데이터와 외부로 반환하는 데이터는 DTO를 사용합니다.**

Entity를 직접 노출하면 내부 데이터 구조가 API와 강하게 결합되고, 의도하지 않은 필드가 노출될 수 있습니다.

### DTO 작성 규칙

- 요청과 응답 DTO를 구분합니다.
- 필요한 필드만 선언합니다.
- DTO는 업무와 팀원이 읽기 쉬운 형태에 따라 일반 `class` 또는 Java `record`를 자율적으로 선택합니다.
- 같은 기능 안에서도 무조건 한 형식으로 통일할 필요는 없지만 선택 이유 없이 상호 변환용 DTO를 중복 생성하지 않습니다.
- Entity와 DTO 사이의 변환은 단순한 경우 명시적으로 작성합니다.

### 입력 검증 방식

- Bean Validation Annotation과 DTO의 명시적 `validate()` 메서드 중 적합한 방식을 자율적으로 사용합니다.
- 단순 길이·필수·범위 검증에는 Bean Validation을 사용할 수 있고 여러 필드 관계나 정규화 이후 조건은 `validate()`로 표현할 수 있습니다.
- 어떤 방식을 선택하더라도 Service가 실행되기 전에 검증하고 공통 `fieldErrors` 또는 업무 오류 구조로 변환합니다.
- 검증을 Controller, DTO와 Service 여러 곳에 의미 없이 복제하지 않습니다. Service는 최신 DB 상태가 필요한 업무 규칙을 검증합니다.

## 1.5. REST API 구현

개별 엔드포인트와 요청·응답 명세는 `api-design.md`를 기준으로 하고, 소스 코드에서는 다음 공통 규칙을 적용합니다.

- URL은 자원을 나타내는 명사를 중심으로 작성합니다.
- 조회, 생성, 수정, 삭제 의도에 맞는 HTTP Method와 상태 코드를 사용합니다.
- 요청값은 DTO로 받고 Bean Validation 또는 명시적 `validate()`로 형식을 검증합니다.
- 입력 형식 검증은 Controller 경계에서, 업무 규칙 검증은 Service에서 처리합니다.
- Entity를 직접 반환하지 않고 응답 DTO로 변환합니다.
- 오류 응답은 프로젝트에서 정한 공통 구조를 사용합니다.
- 목록 조회에 페이징이 필요하면 페이지 번호, 크기, 정렬 기준을 API 설계 문서에 명시합니다.
- 날짜와 시간의 표현 방식과 타임존은 프로젝트 전체에서 통일합니다.

## 1.6. 예외 처리

**예외는 각 Controller에서 개별적으로 처리하지 않고 중앙 예외 처리기에서 공통으로 처리합니다.**

Spring의 `@RestControllerAdvice`, `@ControllerAdvice`를 활용합니다.

### 예외 클래스
예시:

- `ResourceNotFoundException`: 요청한 데이터가 존재하지 않음
- `DuplicateResourceException`: 중복이 허용되지 않는 데이터
- `BusinessException`: 업무 규칙 위반
- `ForbiddenException`: 작업 권한 부족

Spring Security의 `AccessDeniedException`과 혼동하지 않도록 사용자 정의 예외는 다른 이름을 사용합니다.

### HTTP 상태 코드 기준

| 상태 | 의미 | 사용 사례 |
|---|---|---|
| 400 | Bad Request | 잘못된 요청값 |
| 401 | Unauthorized | 인증되지 않은 사용자 |
| 403 | Forbidden | 접근 권한이 없는 사용자 |
| 404 | Not Found | 존재하지 않는 데이터 |
| 409 | Conflict | 중복 데이터, 현재 상태와 충돌하는 요청 |
| 500 | Internal Server Error | 예상하지 못한 서버 오류 |

모든 업무 오류를 400으로 반환하지 않습니다.

예를 들어 이미 사용 중인 이메일로 회원가입을 요청했다면 409를 사용하는 정책을 채택할 수 있습니다.

### 예외 처리 규칙

- 예상 가능한 업무 오류는 의미 있는 예외로 표현합니다.
- 모든 오류를 `RuntimeException` 하나로 처리하지 않습니다.
- `catch (Exception e)`로 예외를 무조건 무시하지 않습니다.
- 예상하지 못한 예외는 중앙 처리기에서 기록하고 500 응답으로 처리합니다.
- 내부 SQL, 스택 트레이스, 비밀번호 등의 민감한 정보를 클라이언트에 노출하지 않습니다.
- REST API는 정의된 오류 응답을 반환하고 CSR이 오류 화면과 안내 메시지를 표시합니다.

## 1.7. 인증과 인가

- 인증은 사용자가 누구인지 확인하는 과정이고, 인가는 해당 사용자가 요청한 작업을 수행할 권한이 있는지 확인하는 과정입니다.
- 구글·네이버 OAuth 2.0 Authorization Code 흐름과 서버 세션 인증을 전체 기능에 일관되게 적용합니다.
- 인가는 화면의 버튼을 숨기는 처리만으로 판단하지 않고 서버에서 다시 검증합니다.
- Controller는 인증된 사용자 정보를 Service에 전달하고, Service는 작업 대상과 사용자의 권한을 기준으로 업무 수행 가능 여부를 판단합니다.
- 자체 비밀번호 회원가입은 지원하지 않으며 비밀번호 컬럼과 비밀번호 처리 코드를 만들지 않습니다.
- 공급자 토큰, Client Secret, 세션 ID 등 인증 정보는 응답, 로그, 예외 메시지에 노출하지 않습니다.
- 공급자 액세스 토큰은 브라우저 저장소로 전달하지 않고 백엔드에서만 처리합니다.
- 세션 쿠키에는 `HttpOnly`, `SameSite`를 적용하고 `Secure`는 `integration`·`prod`에서 필수로 사용합니다. localhost 전용 `local`만 `Secure=false`를 허용하며 상태 변경 API에는 CSRF 검증을 적용합니다.
- OAuth 로그인 시작 시 익명 세션 ID의 Hash를 시도 데이터에 연결하고 Callback에서 같은 세션인지 검증합니다. 원문 세션 ID와 OAuth `state`는 로그에 남기지 않습니다.
- STOMP `CONNECT`도 세션·허용 Origin·CSRF를 검증하고 인바운드 `SEND`·`MESSAGE`는 기본 거부합니다. 화면에서 메시지를 보내지 않는다는 전제만으로 서버 인가를 생략하지 않습니다.
- 서버 세션의 비활성 만료시간은 7일이며 사용자당 최대 3개를 허용합니다. 초과 로그인 시 가장 오래 생성된 세션을 무효화합니다.
- 신규 소셜 가입은 이메일 제공이 필수이고 기존 회원 로그인은 공급자와 공급자 사용자 식별자를 우선합니다.
- 다른 사용자의 구매·보유 아이템·환불 자원은 존재 여부를 숨기도록 `404`, 존재하는 관리 기능의 역할 부족은 `403`으로 처리합니다. Controller의 Path ID만 믿지 않고 Service가 현재 사용자 소유권과 역할을 확인합니다.
- 최초 전체 관리자 외에는 환경변수로 역할을 부여하지 않으며 공개 요청 DTO에 `globalRole`을 추가하지 않습니다.

### 인터셉터

인터셉터는 프로젝트에서 선택한 인증·인가 구조에 맞춰 공통 HTTP 요청 처리가 필요할 때 사용합니다.

- 요청 처리 흐름과 관련된 부가 기능
- Spring Security 등 선택한 기술과 충돌하지 않는 범위의 공통 권한 확인

## 1.8. JPA와 데이터베이스 접근

### 공통 컬럼 매핑

- Entity 기본키는 `Long`과 `BIGINT AUTO_INCREMENT`를 사용합니다.
- 상태 Enum은 `@Enumerated(EnumType.STRING)`으로 저장합니다.
- 시각은 UTC 기준으로 저장하고 KST 업무 날짜가 필요한 경우 `LocalDate` 컬럼을 별도로 사용합니다.
- 모든 Entity에 논리 삭제와 `@Version`을 일괄 적용하지 않고 설계서에서 지정한 생명주기와 동시성 대상에만 적용합니다.
- JSON 컬럼에 검색·조인·정산의 핵심 값을 숨기지 않습니다.

**JPA는 Spring Data JPA의 Repository 인터페이스를 기본으로 사용합니다.**

### 기본 CRUD

Repository는 일반적으로 `JpaRepository<Entity, ID>`를 확장하여 작성합니다.

이를 통해 기본적인 저장, 조회, 삭제, 페이징 기능을 사용할 수 있습니다.

### 조건 검색

조건 검색은 다음 기준으로 작성해주세요.

1. 간단한 조건 검색은 메서드 이름 기반 쿼리를 사용합니다.
2. 메서드 이름이 지나치게 길어지거나 복잡해지면 `@Query`를 사용합니다.

JPQL을 사용하는 경우에도 읽기 쉬운 쿼리를 작성해주세요.

### 영속성 컨텍스트와 변경 감지

**영속 상태의 Entity를 수정할 때는 변경 감지를 활용합니다.**

Service의 트랜잭션 안에서 조회한 Entity의 상태를 변경하면 JPA가 변경 사항을 감지하여 DB에 반영할 수 있습니다.

다만 조회한 Entity가 영속 상태인지, 트랜잭션이 정상적으로 적용되는지 확인해야 합니다.

### 연관관계와 조회 성능

**연관관계는 기본적으로 LAZY 로딩을 권장합니다.**

필요한 데이터를 조회하는 시점에 로딩 전략을 검토해주세요.

- N+1 문제가 발생하지 않는지 확인합니다.
- 필요한 경우 `JOIN FETCH` 등을 활용합니다.
- 모든 연관관계를 무조건 한 번에 조회하지 않습니다.

복잡한 쿼리를 반드시 JPQL로 해결할 필요는 없습니다. SQL이 더 적합한 경우에는 Native Query 등도 사용할 수 있지만, 먼저 팀원들과 구현 방식을 상의해주세요.

## 1.9. 트랜잭션 관리

**트랜잭션은 기본적으로 Service 계층에서 관리합니다.**

트랜잭션은 여러 데이터 변경 작업을 하나의 작업 단위로 묶어 데이터의 일관성을 유지하기 위해 사용합니다.

### 기본 규칙

- 데이터 변경 작업에는 `@Transactional`을 사용합니다.
- 조회 전용 Service 메서드에는 `@Transactional(readOnly = true)` 사용을 권장합니다.
- 여러 Repository를 호출하는 작업은 필요한 범위 전체를 하나의 트랜잭션으로 처리합니다.
- Controller에서 트랜잭션을 관리하지 않습니다.
- 외부 시스템 Adapter에는 업무 트랜잭션을 선언하지 않습니다.
- WebSocket과 후속 이벤트는 `@TransactionalEventListener`의 `AFTER_COMMIT` 단계에서 발행합니다.
- 비관적 잠금 조회는 `find...ForUpdate`처럼 잠금 의도가 드러나는 Repository 메서드 이름을 사용합니다.

### 주의사항

- 불필요하게 긴 트랜잭션을 만들지 않습니다.
- 외부 API 호출이나 시간이 오래 걸리는 작업을 DB 트랜잭션 안에 넣을 때에는 신중하게 검토합니다.

포트원 결제 조회·취소는 다음 세 단계로 분리합니다.

1. 짧은 트랜잭션에서 최신 상태를 검사하고 처리권을 선점합니다.
2. 트랜잭션 밖에서 포트원 API를 호출합니다.
3. 새 짧은 트랜잭션에서 결과와 이력을 확정합니다.

고정된 잠금 순서는 Service Javadoc과 동시성 테스트에 기록합니다. 외부 호출 결과가 불명확하면 성공·실패를 추정하지 않고 설계된 `UNKNOWN` 상태로 전환합니다.

특히 여러 데이터의 변경이 함께 성공하거나 실패해야 하는 경우에는 트랜잭션 범위를 먼저 설계해주세요.

## 1.10. 공통 코드와 재사용

**공통화는 중복된 코드의 양이 아니라, 동일한 책임을 공유하는지를 기준으로 판단합니다.**

여러 곳에서 비슷한 코드가 등장했다고 해서 무조건 하나의 메서드로 합칠 필요는 없습니다.

예를 들어 다음과 같이 판단해주세요.

- 여러 Controller에서 반복되는 HTTP 요청 공통 처리는 별도 공통 구성요소를 검토합니다.
- 여러 도메인에서 사용하는 기술적인 기능은 `_core`에 배치할 수 있습니다.
- 특정 도메인에서만 사용하는 업무 규칙은 해당 Service나 Entity에 둡니다.

## 1.11. 테스트

- 이번 프로젝트에서는 테스트 종류를 많이 늘리지 않고 **핵심 업무 규칙 자동 테스트와 전체 흐름 수동 테스트** 두 가지에 집중합니다.
- 백엔드 자동 테스트는 Spring Boot가 기본 제공하는 JUnit 5를 사용합니다. 필요한 경우 Mockito로 Repository나 외부 연동의 반환값만 대신합니다.
- Docker와 Testcontainers는 사용하지 않습니다. MySQL 동작이 필요한 Repository·잠금 테스트는 팀원의 로컬 MySQL에 `shared_creature_game_test` 데이터베이스를 만들어 실행합니다.
- 테스트 데이터는 각 테스트가 시작할 때 필요한 최소 사용자·그룹·퀘스트만 만들고, 다른 테스트가 남긴 데이터에 의존하지 않습니다.
- 포트원이나 Gemini처럼 비용·외부 상태가 발생하는 시스템은 자동 테스트에서 직접 호출하지 않습니다. `PaymentGateway`, `TextProofreader`의 가짜 구현으로 성공·실패·응답 불명 또는 잘못된 형식 흐름을 확인합니다.
- 자동 테스트의 최소 필수 범위는 그룹 정원 동시 가입, 퀘스트 중복 완료 방지, 시즌 종료·퀘스트 완료 경합, 탈퇴·퀘스트 완료 경합, 최초 장착 후 환불 불가와 동일 환불 중복 처리입니다.
- 경계·복구 테스트는 1인 주 관리자 탈퇴 시 그룹 비활성화, 5분 지난 `CANCEL_PENDING`, 장기 Webhook `PENDING`·`FAILED`, 사진 마감 직전 처리, 저장된 활동 시각 72시간 10분 경계와 그룹 이동 전 활동의 `AGGREGATE` 제외를 고정 시각·가짜 Adapter로 확인합니다.
- 보안 경계 테스트에는 다른 세션의 OAuth Callback 거부, STOMP `SEND`·다른 그룹 구독 거부, 다른 사용자의 구매·아이템·환불 ID 접근 시 `404`, 관리자 역할 부족 시 `403`을 포함합니다.
- 호출량 테스트에는 OAuth 세션·IP 제한, Gemini 분당·일일·동시 호출 제한, 사진 분당·배정별 시도·파서 동시 처리·5초 Timeout 경계를 포함합니다.
- 로그인, 화면 이동, WebSocket 재연결, PortOne 테스트 결제와 관리자 화면은 체크리스트를 보고 브라우저에서 수동 검증합니다. 모바일 OAuth·결제 Redirect와 Webhook은 `integration` Profile의 임시 공개 HTTPS 주소에서 확인합니다.
- LLM이 테스트 코드를 작성해도 테스트 이름, 준비 데이터, 기대 결과를 팀원이 읽고 직접 실행해 확인합니다.

# 2. 프론트엔드 (Full CSR·Vanilla JavaScript·Bootstrap v5)

## 2.0. 기술 기준

- React·Vue 같은 UI Framework를 사용하지 않고 Vanilla JavaScript ES Module을 사용합니다.
- 개발 서버와 Production Build는 Vite를 사용합니다.
- 반응형 Layout과 공통 UI Component는 Bootstrap v5.3 계열을 npm Package로 고정해 사용합니다.
- Bootstrap CSS·JavaScript를 CDN과 npm에서 동시에 불러오지 않습니다.
- Bootstrap JavaScript Plugin은 실제 사용하는 Component만 Import하고 jQuery를 추가하지 않습니다.
- 테마는 `<html data-bs-theme="light|dark">`와 프로젝트 CSS Variable Override를 함께 사용합니다.
- 기본 History API Router와 Route Guard의 구현은 스피어 프로토콜에서 생성하며, 기능 개발자는 확정된 Route 등록 계약을 사용합니다.

### 처음 참여하는 팀원을 위한 동작 설명

Vanilla JavaScript 화면은 다음 네 단계만 반복한다고 이해합니다.

1. Router가 현재 주소에 맞는 `pages` 파일을 선택합니다.
2. Page가 `api` 함수로 서버 데이터를 요청합니다.
3. 받은 데이터를 DOM Element에 넣고 버튼 Event를 연결합니다.
4. 다른 화면으로 이동하면 Page가 등록했던 Event와 Timer를 정리합니다.

Page에서 URL, Cookie, 공통 오류 형식을 직접 처리하지 않습니다. Page는 `getCreatureHome()`처럼 의미가 드러나는 API 함수를 호출하고, 공통 `api` 모듈이 실제 `fetch`와 오류 변환을 담당합니다. 처음 작성할 때는 비슷한 기존 Page 하나를 복사한 뒤 제목, API 함수와 Event만 바꾸는 방식을 권장합니다.

## 2.1. 모듈 구조

```text
frontend/src
├── main.js
├── app
│   ├── bootstrap.js
│   ├── router
│   └── state
├── api
├── pages
├── features
├── components
├── realtime
├── payment
├── charts
└── styles
```

- `pages`는 Route 단위 화면 조립과 생명주기를 담당합니다.
- `features`는 인증, 그룹, 크리처, 퀘스트, 아바타, 결제와 관리자 같은 업무별 UI·API 조합을 담당합니다.
- `components`는 특정 업무 상태를 소유하지 않는 공통 DOM 구성요소만 둡니다.
- `api`는 `fetch`, Cookie Credential, CSRF, 공통 Envelope와 `ApiError` 변환을 담당합니다.
- `realtime`, `payment`, `charts`는 각각 STOMP, 포트원 Browser SDK와 Google Charts의 외부 경계입니다.
- `app/state`는 인증·테마·실시간 연결처럼 애플리케이션 전체에서 필요한 최소 상태만 관리합니다.
- JavaScript 파일 이름은 소문자 Kebab Case, Export 함수·변수는 Camel Case를 사용합니다.
- 범용 전역 Event Bus와 여러 기능을 한 파일에 모은 거대한 `app.js`를 만들지 않습니다.

## 2.2. Page Module 생명주기

각 Route Page는 `mountPage(context)`를 Export하고 화면을 해제하는 Cleanup 함수를 반환합니다.

```javascript
/**
 * 화면을 Root Element에 Mount합니다.
 *
 * @param {PageContext} context 화면 Context
 * @returns {Promise<() => void>} 화면 해제 함수
 */
export async function mountPage(context) {
    // 데이터 조회, DOM 구성과 Event 등록

    return () => {
        // Event, Timer, WebSocket과 Bootstrap Instance 정리
    };
}
```

`PageContext`는 `root`, `params`, `query`, `signal`, `currentUser`, `navigate`만 제공합니다. Router는 화면 이동 시 이전 Cleanup을 먼저 호출하고 `AbortController`로 진행 중인 조회를 취소합니다.

- 사용자 입력과 API 문자열은 `innerHTML`로 삽입하지 않고 `textContent` 또는 안전한 DOM API를 사용합니다.
- 반복되는 정적 Markup은 `<template>`을 사용할 수 있지만 사용자 데이터를 문자열 보간하지 않습니다.
- Bootstrap Modal, Offcanvas, Toast와 Popover는 필요한 Plugin만 Import하고 Page Cleanup에서 생성한 Instance를 `dispose()`합니다.
- 핵심 API·Router·실시간 모듈은 JSDoc Type과 `// @ts-check`를 사용해 DTO·함수 계약을 검사합니다.
- UI Event Listener, Timer와 Subscription은 등록한 모듈이 해제 책임도 함께 가집니다.

## 2.3. CSR 화면 구성

- 프론트엔드는 백엔드 REST API를 호출해 화면 상태를 구성하고 서버 렌더링 템플릿에 의존하지 않습니다.
- 헤더, 내비게이션, 로그인 상태 처리처럼 반복되는 UI와 상태 로직은 공통 컴포넌트로 분리합니다.
- 로그인 완료 후 현재 사용자 API를 호출해 온보딩, 그룹 소속과 역할에 맞는 경로로 이동합니다.
- 인증 실패 시 로그인 화면으로 이동하고, 인가 실패는 역할에 맞는 안내 화면 또는 메시지로 처리합니다.

## 2.4. HTML 폼 검증

- 입력값의 종류에 맞는 `type`을 사용하고, 필요한 항목에는 `required`를 지정합니다.
- 입력 규칙에 맞춰 `minlength`, `maxlength`, `min`, `max`, `step`, `pattern`, `accept` 등 HTML 폼 검증 속성을 사용합니다.
- `placeholder`는 입력 예시나 힌트로만 사용하고 `label`을 대신하지 않습니다.
- 브라우저의 폼 검증은 사용자 편의를 위한 1차 검증이며, 서버 DTO의 Bean Validation 또는 `validate()` 검증을 대체하지 않습니다.
- HTML과 서버의 검증 조건은 서로 모순되지 않도록 길이, 범위와 필수 여부를 동일하게 유지합니다.
- `novalidate`, `formnovalidate`는 브라우저 검증을 의도적으로 끄는 이유가 있을 때만 사용합니다.
- 기본 HTML 속성으로 표현하기 어려운 검증만 JavaScript로 보완하고, 오류 이유를 사용자가 확인할 수 있게 표시합니다.

## 2.5. JavaScript

- JavaScript는 가능하면 별도 파일로 분리하고 HTML에 인라인 스크립트를 반복하여 작성하지 않습니다.
- 함수와 모듈은 하나의 명확한 책임을 갖도록 작성합니다.
- HTML에 `onclick` 등의 이벤트 처리 코드를 직접 작성하기보다 JavaScript에서 이벤트를 등록하는 방식을 권장합니다.
- DOM 선택자는 화면 구조가 변경되어도 의도를 알 수 있는 이름을 사용합니다.

## 2.6. CSS

- 공통 스타일과 화면·컴포넌트 전용 스타일을 구분합니다.
- 클래스 이름은 스타일의 모양보다 요소의 역할을 알 수 있도록 작성합니다.
- `!important`는 기존 스타일 충돌을 피하기 위해 반복적으로 사용하지 않습니다.
- 화면 크기가 달라져도 주요 기능을 사용할 수 있는지 확인합니다.
- 버튼, 입력 필드, 링크의 focus 표시를 임의로 제거하지 않습니다.
- Bootstrap Sass 변수와 프로젝트 CSS 변수를 연결하되 Bootstrap Source 파일을 직접 수정하지 않습니다.
- `data-bs-theme`은 앱 본문을 렌더링하기 전에 적용하고 Component별 색상은 확정된 Theme Token을 사용합니다.

## 2.7. 프론트엔드 테스트와 목 데이터

이번 프로젝트에서는 별도의 프론트엔드 자동 테스트 Framework를 필수로 사용하지 않습니다. 화면 테스트는 Vite 개발 서버와 브라우저에서 수행합니다.

**목 데이터(Mock Data)**는 백엔드가 보낼 응답과 같은 모양으로 미리 작성한 가짜 JavaScript 객체입니다. 백엔드 기능이 아직 완성되지 않아도 화면 담당자가 목록, 빈 화면과 오류 안내를 먼저 확인할 수 있습니다.

```text
frontend/src/mocks
├── mock-data.js     # 사용자, 크리처, 퀘스트 등 가짜 응답
└── mock-api.js      # 요청 주소별로 가짜 응답을 반환
```

- `.env.local`의 `VITE_USE_MOCK_API=true`로 목 모드를 켜고 `false`로 실제 서버 호출로 되돌립니다.
- Page는 목 모드 여부를 확인하지 않습니다. 공통 `api` 모듈만 실제 API와 `mock-api.js` 중 하나를 선택합니다.
- 목 응답의 필드 이름과 구조는 `api-design.md`의 실제 응답 DTO와 같게 유지합니다.
- 최소한 정상 데이터, 빈 목록, 그룹 없는 사용자, 24시간 가입 제한, 권한 없음과 서버 오류 예시를 준비합니다.
- 결제 성공처럼 실제 외부 연동이 필요한 동작은 목 데이터만으로 완료됐다고 판단하지 않고 마지막에 PortOne 테스트 환경에서 다시 확인합니다.

화면 담당자는 다음 순서로 테스트합니다.

1. 목 모드를 켜고 개발 서버를 실행합니다.
2. 확인할 목 데이터 시나리오를 `mock-data.js`에서 선택합니다.
3. 브라우저에서 화면, 버튼과 모바일 크기를 확인합니다.
4. 개발자 도구 Console에 오류가 없는지 확인합니다.
5. 목 모드를 끄고 완성된 백엔드와 같은 흐름을 한 번 더 확인합니다.

# 3. 포매팅과 실행 환경

## 3.1. 포매팅

- 공통 들여쓰기, 문자 인코딩과 줄 끝은 `.editorconfig`로 맞춥니다.
- Java는 팀에서 공유한 IDE 자동 포맷 설정을 사용하고 별도 Checkstyle·Spotless 도입은 필수로 하지 않습니다.
- JavaScript, CSS와 JSON은 Prettier를 사용하되 기능 구현을 막는 복잡한 추가 규칙은 두지 않습니다.
- SQL의 콤마 위치와 세로 정렬은 확정된 단일 형식을 강제하지 않습니다. 작성자가 읽기 쉬운 형식을 선택하되 같은 파일과 같은 쿼리 안에서는 일관되게 작성합니다.

## 3.2. Spring Profile과 비밀값

- Spring Profile은 `local`, `test`, `integration`, `prod` 네 가지를 사용합니다.
- `local`은 팀원 PC의 로컬 MySQL과 localhost HTTP, `test`는 `shared_creature_game_test`, `integration`은 팀원 PC의 애플리케이션을 임시 공개 HTTPS 터널로 검증하는 환경, `prod`는 배포 환경입니다.
- 세션 쿠키 `Secure=false`는 localhost 전용 `local` Profile에서만 허용합니다. `integration`과 `prod`는 `Secure=true`를 사용하고 HTTPS가 아니면 실행 전 검증에서 실패시킵니다.
- Docker는 어느 Profile에서도 전제로 사용하지 않습니다.
- 공통값은 `application.yml`, 환경별 비밀이 아닌 차이는 `application-{profile}.yml`에 둡니다.
- DB 비밀번호, OAuth Client Secret, PortOne API·Webhook Secret과 Gemini API Key는 Git에 저장하지 않고 환경변수로 전달합니다.
- 최초 관리자용 `INITIAL_SUPER_ADMIN_PROVIDER`, `INITIAL_SUPER_ADMIN_EMAIL`도 환경변수로 전달하며 실제 이메일은 `.env.example`이나 문서에 기록하지 않습니다.
- 전체 관리자가 아직 없으면 두 초기 관리자 변수를 함께 요구하고 한쪽 누락·지원하지 않는 공급자·잘못된 이메일 형식은 시작 검증에서 실패시킵니다. 기존 전체 관리자가 있으면 환경변수로 역할을 다시 변경하지 않습니다.
- `integration`은 `APP_BASE_URL`에 현재 임시 HTTPS Origin을 설정합니다. 터널 제품은 고정하지 않고 실행 담당자와 등록 절차만 팀 실행 가이드에 둡니다.
- `.env.example`에는 실제 값 없이 필요한 변수 이름과 설명만 기록합니다.
- `test` Profile은 실제 Google·Naver OAuth, PortOne 또는 Gemini API를 호출하지 않고 가짜 Adapter를 사용합니다.
- DB 시각은 UTC로 저장하고 시즌·퀘스트·가입 제한 같은 업무 시각은 `Asia/Seoul`로 계산합니다.

## 3.3. 프론트엔드 실행 환경

- 개발 중 Vite는 `/api`, `/oauth2`, `/ws` 요청을 로컬 Spring Boot 서버로 Proxy합니다.
- 통합 검증 시 터널은 Vite 개발 서버 한 곳만 공개하고 Vite Proxy가 `/api`, `/oauth2`, `/ws`를 Spring Boot로 전달합니다. OAuth Callback, PortOne Redirect와 Webhook도 같은 `APP_BASE_URL`을 사용합니다.
- 운영에서는 프론트엔드와 API를 같은 Origin으로 제공합니다.
- 브라우저에 노출되면 안 되는 비밀값은 `VITE_` 환경변수에 넣지 않습니다.
- PortOne Store ID와 Channel Key처럼 브라우저에 필요한 공개 설정은 백엔드 설정 조회 API로 전달합니다.
- `VITE_USE_MOCK_API`는 로컬 화면 개발 전용이며 Production Build에서는 사용할 수 없게 합니다.
