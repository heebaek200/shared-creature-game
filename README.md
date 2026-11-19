# 공동 육성 게임 — 다 같이 키우는 생명체

- [프로젝트 설계 문서 보기](docs/index.md)
- [GitHub Projects 보기](https://github.com/heebaek200/shared-creature-game/projects)
- [팀 프로젝트 일일 리포트 보기](https://github.com/heebaek200/shared-creature-game/discussions)

## 프로젝트 정보

### 팀 구성 및 역할

- **개발 인원:** 4명

| 이름 | GitHub | 주요 담당 역할 |
|---|---|---|
| **이희백** | [heebaek200](https://github.com/heebaek200) | **프로젝트 관리:** GitHub Projects 운영, 설계 문서 관리 및 보완<br>**백엔드:** 프로젝트 기반 구현, Scheduler, WebSocket, 그룹, 관리자 등<br>**프론트엔드:** 퀘스트, WebSocket 연동, 상점·결제, 관리자 등 |
| **이수현** | [is-hyun](https://github.com/is-hyun) | **기술 검증:** 그래픽 리소스 확보 및 활용 가능성 검토<br>**프론트엔드:** 크리처 홈, 공통 레이아웃·테마, 아바타 화면 등 |
| **양현재** | [a67286268-oss](https://github.com/a67286268-oss) | **기술 검증:** JPEG EXIF 기반 사진 인증<br>**백엔드:** 퀘스트, EXIF 인증, 아바타·아이템, 랭킹, 환불, 리팩터링 등 |
| **김희성** | [asd27429697-design](https://github.com/asd27429697-design) | **기술 검증:** 크리처·아바타의 브라우저 표현 방식<br>**백엔드:** Naver OAuth, Gemini AI 교정<br>**프론트엔드:** 회원가입·그룹 관리, Naver OAuth, Gemini AI 교정 등 |

※ 담당 역할은 주요 구현 및 기여 영역을 중심으로 요약했습니다.

## 주요 기술 스택

| 분류 | 기술 | 프로젝트에서의 역할 |
|---|---|---|
| 언어·빌드 | Java 21, JavaScript ES Modules, Gradle, npm | Spring Boot 서버와 브라우저 애플리케이션 구현, 의존성·빌드 관리 |
| 백엔드 | Spring Boot 4.1.1, Spring MVC, Spring Security, Spring Data JPA | REST API, 세션 인증·역할 인가, 트랜잭션과 영속성 관리 |
| 프론트엔드 | Vite 7, Vanilla JavaScript, Bootstrap 5, HTML/CSS | History API 기반 CSR/SPA, 반응형 화면과 공통 테마 구성 |
| 데이터베이스·세션 | MySQL 8, Spring Session JDBC | 업무 데이터와 로그인 세션 영속화, UNIQUE·락 기반 정합성 보장 |
| 인증 | OAuth 2.0 Authorization Code, OpenID Connect | Google·Naver 소셜 인증, 공급자 신원 검증과 서버 세션 로그인 |
| 실시간 | WebSocket, STOMP | 그룹별 상태 이벤트·Presence·아바타 동기화와 Snapshot 복구 신호 전달 |
| 외부 서비스 | PortOne V2, Gemini API, Metadata Extractor | 테스트 결제·환불, 그룹 소개 교정, JPEG EXIF 추출 |
| 정기 작업 | Spring Scheduler | 시즌·퀘스트·부화, 결제·환불 복구, 사진 보존 기한 정리 |
| 테스트 | JUnit 5, Spring Boot Test, Spring Security Test | 단위·통합·동시성·보안 경계 검증 |

핵심 특징은 DB Commit을 최종 상태로 삼고 WebSocket은 결과 전달에만 사용하는 점, 비관적·낙관적 락과 UNIQUE 제약·멱등성 키를 함께 사용해 중복 처리를 막는 점, 외부 PG 호출과 DB 트랜잭션을 분리하고 Webhook·Scheduler로 결과 불명 상태를 복구하는 점입니다. 사진 인증은 JPEG Signature와 EXIF를 서버에서 다시 검증하고 제한된 Worker와 Timeout으로 작업량을 제어합니다.

> 일부 영역은 최종 확정 설계를 기준으로 먼저 문서화했습니다. 발표·배포 전에는 실제 통합 코드와 테스트 결과를 다시 대조합니다.

**[기술 스택 및 아키텍처 설계·구현 해설](docs/tech-stack.md)**

## 로컬 구동 방법

### 1. 준비물

- Java 21
- MySQL 8
- Node.js 20 이상과 npm

Docker, H2, Flyway와 Liquibase는 사용하지 않습니다. 일반 업무 테이블·컬럼과 일반 제약은 JPA `ddl-auto=update`로 만들고, Spring Session 테이블과 MySQL STORED 생성 컬럼·조건부 UNIQUE는 `db/special-schema.sql`로 관리합니다.

### 2. 데이터베이스 준비

로컬 MySQL 관리자 계정으로 다음 데이터베이스와 개발 계정을 준비합니다. 비밀번호는 예시를 그대로 사용하지 않습니다.

```sql
CREATE DATABASE shared_creature_game CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE shared_creature_game_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'shared_creature'@'localhost' IDENTIFIED BY 'replace-with-local-password';
GRANT ALL PRIVILEGES ON shared_creature_game.* TO 'shared_creature'@'localhost';
GRANT ALL PRIVILEGES ON shared_creature_game_test.* TO 'shared_creature'@'localhost';
```

`.env.example`을 `.env`로 복사한 뒤 `LOCAL_DB_*`, `TEST_DB_*`를 자신의 로컬 계정에 맞게 설정합니다. `.env`는 Git에 포함하지 않습니다.

### 3. Backend 실행

Windows PowerShell 기준입니다.

```powershell
.\gradlew.bat bootRun
```

기본 주소는 `http://localhost:8080`입니다. 최초 실행 시 JPA가 업무 테이블을 생성한 뒤 `data.sql`이 시즌·크리처 종·퀘스트·상품의 기본 샘플 데이터를 넣습니다.

이미 이전 스키마로 실행했던 로컬 DB는 `ddl-auto=update`가 기존 `NOT NULL` 완화를 자동 반영하지 않습니다. 데이터를 삭제하거나 DB를 재생성하지 말고 Backend를 중지한 상태에서 다음 보정 SQL을 한 번 실행합니다.

```powershell
mysql -u shared_creature -p shared_creature_game < docs/sql/local-schema-repair.sql
```

신규 DB에는 이 보정이 필요하지 않습니다. STORED 생성 컬럼과 조건부 UNIQUE는 애플리케이션 시작 시 `special-schema.sql`이 멱등 적용합니다.

테스트는 별도 `shared_creature_game_test` 데이터베이스를 사용합니다.

```powershell
.\gradlew.bat test
```

#### 로컬 테스트용 전체 관리자 계정 준비

관리자 대시보드 및 관리 기능을 테스트하려면 `SUPER_ADMIN` 권한이 필요합니다.

신규 Google 계정은 `.env`의 `INITIAL_SUPER_ADMIN_PROVIDER`와
`INITIAL_SUPER_ADMIN_PROVIDER_USER_ID` 설정을 통해 최초 전체 관리자로
가입할 수 있습니다. 단, 이미 활성 전체 관리자가 존재하거나 해당 계정이
가입된 상태라면 이 방식으로 권한을 부여할 수 없습니다.

이미 가입한 일반 계정은 로컬 개발환경 전용 SQL을 사용해 승격할 수 있습니다.

1. Google 또는 Naver 로그인을 통해 회원가입을 완료합니다.
2. MySQL의 `users` 테이블에서 해당 계정의 `id`를 확인합니다.
3. `docs/sql/local-super-admin-promotion.sql`의 대상 사용자 ID를 지정합니다.
4. SQL 실행 결과를 확인한 뒤 트랜잭션을 확정합니다.
5. 로그아웃 후 다시 로그인하여 `/admin` 화면에 접근합니다.

> **주의:** 이 SQL은 로컬 개발 DB에서만 수동 실행합니다.
> 운영 DB에서는 사용하지 않으며, 실행 전 대상 계정과 현재 권한을 확인해야 합니다.

### 4. Frontend 실행

```powershell
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:5173`에 접속합니다. Vite가 `/api`, `/oauth2`, `/ws`를 Backend로 Proxy합니다.

Backend 없이 화면 견본을 확인할 때에는 `frontend/.env.local`에 다음 값을 설정합니다.

```properties
VITE_USE_MOCK_API=true
```

Production Build에서는 목 모드를 켤 수 없습니다.

```powershell
npm run build
```

### 5. Google OAuth 로컬 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에서 Web Application OAuth Client를 만듭니다.
2. 승인된 JavaScript Origin에 `http://localhost:5173`을 등록합니다.
3. 승인된 Redirect URI에 `.env`의 `GOOGLE_REDIRECT_URI`와 정확히 같은 `http://localhost:5173/oauth2/callback/google`을 등록합니다.

[![Google OAuth 클라이언트 설정 예시](docs/images/google_oauth.png)](https://github.com/heebaek200/shared-creature-game/blob/main/docs/images/google_oauth.png)

4. Client ID와 Client Secret을 각각 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`에 설정합니다. 최초 전체 관리자로 사용할 Google 계정의 OpenID Connect `sub` 값을 `INITIAL_SUPER_ADMIN_PROVIDER_USER_ID`에 설정하고 `INITIAL_SUPER_ADMIN_PROVIDER=GOOGLE`을 유지합니다.
5. Google Scope는 `openid profile email`만 사용합니다.

[![Google OAuth Scope 설정 예시](docs/images/google_oauth_scope.png)](https://github.com/heebaek200/shared-creature-game/blob/main/docs/images/google_oauth_scope.png)

6. 로그인 시작·`state`·`nonce`·시작 세션 결속과 가입 대기·내부 사용자 연결은 구현되어 있습니다.
7. Backend는 Authorization Code를 Token으로 교환하고 ID Token과 nonce를 검증한 뒤 UserInfo를 조회합니다. 공급자 Token은 Browser나 DB에 저장하지 않습니다.

Google 공식 구현 기준은 [Web Server OAuth 2.0](https://developers.google.com/identity/protocols/oauth2/web-server)과 [OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)를 확인합니다.

### 6. Naver OAuth 로컬 설정

1. [Naver Developers](https://developers.naver.com/)에서 **Application → 애플리케이션 등록**으로 이동하여 애플리케이션을 생성합니다.
2. 사용 API에서 **네이버 로그인**을 선택하고, 제공 정보에 **이메일 주소**를 포함합니다. 현재 구현에서는 사용자 식별자(`id`)와 이메일(`email`)이 필수이며, 닉네임(`nickname`) 또는 이름(`name`)을 추천 닉네임으로 사용합니다.
3. 로그인 오픈 API 서비스 환경에서 **PC 웹**을 선택하고 다음 주소를 등록합니다.
   - 서비스 URL: `http://localhost:5173`
   - Callback URL: `http://localhost:5173/oauth2/callback/naver`
4. 발급받은 Client ID와 Client Secret을 프로젝트 루트 `.env`에 설정합니다. `NAVER_REDIRECT_URI`는 개발자센터에 등록한 Callback URL과 정확히 일치해야 합니다.

   ```properties
   NAVER_CLIENT_ID=your_naver_client_id
   NAVER_CLIENT_SECRET=your_naver_client_secret
   NAVER_REDIRECT_URI=http://localhost:5173/oauth2/callback/naver
   ```

5. **개발 중** 상태의 애플리케이션은 등록자 및 관리자·테스터로 등록된 계정만 로그인할 수 있습니다. 다른 팀원이 테스트하려면 개발자센터의 **멤버관리**에서 해당 네이버 계정을 테스터로 추가합니다.
6. Backend와 Frontend를 실행한 뒤 `http://localhost:5173/login`에서 **Naver로 계속하기** 버튼으로 로그인합니다. 신규 사용자는 닉네임 설정을 거쳐 회원가입을 완료합니다.
7. 로그인 시작·`state` 검증·시작 세션 결속과 가입 대기·내부 사용자 연결은 구현되어 있습니다. Backend는 Authorization Code를 Access Token으로 교환한 뒤 네이버 사용자 정보 API(`/v1/nid/me`)를 호출하여 사용자 식별자와 이메일을 확인합니다. 공급자 Token은 Browser나 DB에 저장하지 않습니다.

Naver 공식 구현 기준은 [네이버 로그인 개발가이드](https://developers.naver.com/docs/login/devguide)와 [네이버 로그인 API 명세](https://developers.naver.com/docs/login/api/api.md)를 확인합니다. 일반 사용자에게 서비스를 공개하려면 별도의 사전 검수가 필요합니다.

### 7. Android 실기기 실행 및 사진 EXIF 인증 테스트

이 프로젝트는 Android 스마트폰의 Chrome 브라우저에서 접속하여 사진 인증 퀘스트를 테스트할 수 있도록 구성되어 있습니다.

로컬 PC에서 Spring Boot와 Vite를 실행하고, **ngrok을 통해 HTTPS 주소를 발급받아 스마트폰에서 접속**하는 방식입니다. 별도의 Android 앱 설치나 APK 빌드는 필요하지 않습니다.

#### 7.1. 준비물

- Spring Boot와 Vite를 실행할 개발 PC
- Android 스마트폰 및 Chrome 브라우저
- GPS 위치 정보를 기록할 수 있는 카메라 앱
- [ngrok](https://ngrok.com/) 계정 및 CLI
- Google 또는 Naver OAuth 테스트용 계정

PC에는 앞서 설명한 [로컬 구동 환경](#로컬-구동-방법)이 준비되어 있어야 합니다.

이번 테스트에서는 스마트폰과 개발 PC가 같은 Wi-Fi에 연결되어 있을 필요는 없습니다. 스마트폰은 ngrok이 제공하는 HTTPS 주소를 통해 개발 PC의 Vite 서버에 접근합니다.

#### 7.2. ngrok HTTPS 주소 확보

ngrok을 설치한 뒤 계정의 인증 토큰을 등록합니다. 인증 토큰 등록 방법은 [ngrok 공식 문서](https://ngrok.com/docs)를 참고합니다.

터미널에서 다음 명령을 실행합니다.

```powershell
ngrok http 5173
```

실행 후 다음과 비슷한 주소가 표시됩니다.

```text
Forwarding  https://example.ngrok-free.dev -> http://localhost:5173
```

여기서 `https://example.ngrok-free.dev`은 설명을 위한 예시입니다. **실제로 발급받은 주소를 이후 설정에 사용해야 합니다.**

ngrok은 테스트를 진행하는 동안 종료하지 않습니다.

#### 7.3. 환경변수 및 OAuth 설정

프로젝트 루트의 `.env` 파일에서 ngrok 주소와 OAuth Callback URL을 설정합니다.

```dotenv
NGROK_DOMAIN_URI=https://example.ngrok-free.dev

GOOGLE_REDIRECT_URI=https://example.ngrok-free.dev/oauth2/callback/google
NAVER_REDIRECT_URI=https://example.ngrok-free.dev/oauth2/callback/naver
```

기존 OAuth Client ID와 Client Secret은 유지합니다.

Google Cloud Console의 OAuth 클라이언트 설정에는 다음 주소를 추가합니다.

| 설정 항목 | 등록 주소 |
|---|---|
| 승인된 JavaScript Origin | `https://example.ngrok-free.dev` |
| 승인된 Redirect URI | `https://example.ngrok-free.dev/oauth2/callback/google` |

Naver Developers를 사용하는 경우에도 서비스 URL과 Callback URL을 다음과 같이 설정합니다.

| 설정 항목 | 등록 주소 |
|---|---|
| 서비스 URL | `https://example.ngrok-free.dev` |
| Callback URL | `https://example.ngrok-free.dev/oauth2/callback/naver` |

두 소셜 로그인 공급자를 모두 설정할 필요는 없습니다. 테스트에 사용할 공급자만 설정해도 됩니다.

OAuth 서비스가 개발·테스트 상태라면 해당 계정이 테스트 사용자로 등록되어 있어야 합니다.

> **주의:** OAuth Callback URL은 `.env` 설정과 공급자 개발자 콘솔에 등록된 주소가 정확히 일치해야 합니다. ngrok 주소가 변경되면 관련 설정도 함께 갱신해야 합니다.

#### 7.4. Backend 및 Frontend 실행

환경변수 설정을 완료한 다음 프로젝트 루트에서 Backend를 실행합니다.

```powershell
.\gradlew.bat bootRun
```

별도의 터미널을 열어 Frontend를 실행합니다.

```powershell
cd frontend
npm install
npm run dev
```

각 서버의 기본 주소는 다음과 같습니다.

| 구분 | 로컬 주소 |
|---|---|
| Spring Boot | `http://localhost:8080` |
| Vite | `http://localhost:5173` |
| Android 접속 | ngrok에서 발급받은 HTTPS 주소 |

Vite가 `/api`, `/oauth2`, `/ws` 요청을 Spring Boot로 프록시하므로, ngrok은 Vite의 `5173` 포트만 연결하면 됩니다.

`frontend/.env.local`에 `VITE_USE_MOCK_API=true`가 설정되어 있다면 해당 설정을 제거하거나 `false`로 변경합니다. **실제 사진 인증에는 Mock API가 아닌 Backend API를 사용해야 합니다.**

#### 7.5. Android Chrome에서 로그인

Android 스마트폰의 Chrome 브라우저를 실행한 뒤 ngrok HTTPS 주소로 접속합니다.

1. 프로젝트 로그인 화면이 표시되는지 확인합니다.
2. Google 또는 Naver 로그인을 진행합니다.
3. 신규 사용자라면 회원가입을 완료합니다.
4. 그룹을 생성하거나 기존 그룹에 가입합니다.
5. 현재 시즌의 그룹 상태가 `ACTIVE`이고 크리처가 준비되어 있는지 확인합니다.
6. `/quests` 화면으로 이동합니다.

사진 인증 API를 호출하려면 로그인한 사용자에게 활성 그룹 멤버십이 필요합니다. 또한 퀘스트 보상까지 확인하려면 활성 시즌과 활성 그룹 시즌 등 보상 지급 조건을 충족해야 합니다.

#### 7.6. 사진 인증용 퀘스트 데이터 준비

현재 프로젝트에는 사진 인증 테스트를 위한 SQL 파일이 포함되어 있습니다.

`src/main/resources/db/testdata_photo_exif.sql`

이 SQL은 별도 테스트 퀘스트나 추가 슬롯을 만들지 않고, 현재 활성 기간의 공식 사진 퀘스트(`D-X-01` 또는 `W-X-01`)를 재사용합니다.

먼저 Backend 실행으로 필요한 DB 테이블이 준비된 상태인지 확인합니다.

개발 PC에서 MySQL에 접속합니다.

```powershell
mysql -u shared_creature -p shared_creature_game
```

프로젝트 루트에서 MySQL 클라이언트를 실행했다면 다음 명령으로 테스트 SQL을 적용할 수 있습니다.

```sql
SET @photo_exif_period_type = 'DAILY';
SET @photo_exif_allow_replacement = 0;
SET @photo_exif_test_user_id = 123;
SOURCE src/main/resources/db/testdata_photo_exif.sql;
```

`@photo_exif_period_type`은 기본 `DAILY`이며 `WEEKLY`도 선택할 수 있습니다. 사용자 ID는 선택 사항입니다. 공식 사진 퀘스트가 이미 배정되어 있으면 `REUSED`, 없으면 `SKIPPED`를 반환합니다. 로컬 DB를 백업한 뒤 안전한 MEDIUM 배정 교체가 필요할 때만 `@photo_exif_allow_replacement = 1`로 실행하며, 이 경우에도 완료·사진 제출 이력이 없는 배정 하나만 `REPLACED` 처리합니다.

실행 결과의 `assignment_id`, 기간, 완료·제출 이력, `test_ready`, 안내 메시지를 확인합니다. 기간이 없거나 배정 구성이 Scheduler 규칙과 다르면 데이터를 만들거나 고치지 않으므로 Scheduler를 먼저 정상 실행합니다.

> 이 SQL은 로컬 개발 DB에서만 실행합니다. 사진 인증에 성공하면 테스트 계정에도 실제 퀘스트 보상이 반영될 수 있습니다.

SQL 적용 후 Android의 `/quests` 화면을 새로고침하여 공식 사진 퀘스트가 표시되는지 확인합니다.

#### 7.7. Android에서 사진 촬영 및 제출

사진 인증은 JPEG 파일에 포함된 **GPS 위치 정보와 EXIF 촬영 시각**을 서버에서 검사합니다.

먼저 Android의 카메라 설정에서 위치 태그 또는 위치 정보 저장 기능을 활성화하고, 카메라 앱의 위치 권한을 허용합니다.

이후 다음 순서로 테스트합니다.

1. 사진 퀘스트의 유효 기간 안에 새로운 사진을 촬영합니다.
2. 사진이 JPEG 형식으로 저장되었는지 확인합니다.
3. Android Chrome의 `/quests` 화면에서 공식 사진 퀘스트(`D-X-01` 또는 `W-X-01`)를 찾습니다.
4. `JPEG 사진 선택`에서 방금 촬영한 원본 사진을 선택합니다.
5. `사진 제출` 버튼을 누릅니다.
6. 화면에 표시되는 인증 결과와 퀘스트 완료 상태를 확인합니다.

현재 API의 주요 제한은 다음과 같습니다.

| 항목 | 조건 |
|---|---|
| 파일 형식 | JPEG (`image/jpeg`) |
| 파일 크기 | 최대 10 MiB |
| EXIF | GPS 위치·촬영 시각 필요 |
| 촬영 시각 | 퀘스트 배정 기간 안 |
| 제출 횟수 | 사용자·배정별 최대 5회 |
| 요청 제한 | 사용자별 분당 최대 5회 |

카카오톡 등 메신저로 전송하거나 편집한 사진은 EXIF 정보가 제거될 수 있으므로, 가능하면 카메라에서 촬영한 원본 파일을 선택합니다.

또한 GPS 데이터의 존재 여부와 좌표 범위는 검증하지만, 사용자가 실제 특정 장소를 방문했는지까지 증명하는 기능은 아닙니다.

#### 7.8. 인증 결과 확인

사진 제출 API는 EXIF 검증 결과와 보상 처리 결과를 반환합니다.

| 결과 | 의미 |
|---|---|
| `VALID` | 사진 검증 성공. 누적 목표를 달성한 경우에만 퀘스트 완료·보상 처리 |
| `INVALID` | EXIF 누락, 촬영 기간 불일치 등으로 인증 실패 |
| `PENDING` | 판정이 아직 확정되지 않은 상태 |

정상 인증 시에는 API 응답의 `verificationStatus`가 `VALID`입니다. `W-X-01` 첫 유효 사진처럼 누적 목표에 도달하지 않았다면 `completionId`는 `null`이며, 목표를 달성한 제출부터 값이 존재합니다.

화면에서 사진 진행도가 반영되는지 확인합니다. 목표를 달성한 경우에는 완료 상태와 보상이 반영되고, 새로고침한 뒤에도 해당 상태가 유지되어야 합니다.

DB에서 최근 사진 제출 판정을 확인하려면 다음 SQL을 사용할 수 있습니다.

```sql
SELECT id
     , quest_assignment_id
     , attempt_number
     , verification_status
     , failure_reason
     , captured_at
     , uploaded_at
  FROM photo_submissions
 ORDER BY id DESC
 LIMIT 5;
```

이 테이블은 사진 판정 이력을 저장합니다. 실제 보상 지급 여부는 화면의 완료·보상 표시와 `quest_completions` 등 관련 완료 기록을 함께 확인합니다.

#### 7.9. 자주 발생하는 문제

| 증상 | 확인할 내용 |
|---|---|
| 스마트폰에서 사이트 접속 불가 | Vite 실행 여부, ngrok 주소 및 연결 상태 |
| `Blocked request` 등 Vite 접근 오류 | `.env`의 `NGROK_DOMAIN_URI`와 실행 중인 ngrok 주소 일치 여부 |
| Google/Naver 로그인 오류 | Redirect URI 등록값, 테스트 계정, 서버 재시작 여부 |
| 로그인 후 세션 오류 | 동일한 ngrok 주소에서 로그인 시작·Callback이 이루어졌는지 확인 |
| 사진 퀘스트가 표시되지 않음 | 테스트 SQL 실행 결과, 활성 시즌, 일일 퀘스트 기간 |
| GPS 정보 누락 | 카메라 위치 태그와 위치 권한, 원본 사진 여부 |
| 촬영 기간 오류 | 촬영 시각이 퀘스트 배정 기간에 포함되는지 확인 |
| 사진 검증은 진행됐지만 보상 실패 | 활성 그룹 멤버십, 그룹 시즌 상태, 기존 퀘스트 완료 여부 |
| 제출 버튼 비활성화 | 제출 횟수 소진, 퀘스트 완료 또는 기간 만료 여부 |

사진 인증에 반복 실패했다면 먼저 원인을 확인합니다. 무조건 재제출하면 배정별 최대 5회 제한에 도달할 수 있습니다.

#### 7.10. 테스트 종료 및 결과 기록

ngrok을 통해 로컬 개발 서버를 외부에 공개하는 동안에는 테스트 계정과 개발 데이터만 사용합니다.

테스트를 마치면 ngrok을 종료하고, 필요하지 않은 외부 접속을 차단합니다.

개인 GPS 좌표가 포함된 원본 사진과 OAuth 인증 정보는 GitHub 저장소에 업로드하지 않습니다.

### 8. PortOne V2 카카오페이 테스트 결제 설정

이 프로젝트는 **PortOne V2와 카카오페이 테스트 채널**을 이용하여 아바타 아이템 구매·환불 기능을 구현합니다.

각 개발자는 자신의 로컬 환경에서 PortOne 테스트 채널과 API 인증 정보를 설정한 후, 실제 Backend API와 로컬 MySQL을 사용하여 결제 기능을 검증할 수 있습니다.

이 절에서는 PortOne 계정 준비부터 카카오페이 테스트 결제 성공 확인까지 안내합니다.

#### 8.1. 준비물

- [PortOne 관리자 콘솔](https://admin.portone.io/) 계정
- Spring Boot와 Vite가 실행되는 로컬 개발 환경
- MySQL 8 및 프로젝트 데이터베이스
- Google 또는 Naver 로그인 테스트 계정

#### 8.2. PortOne 계정 및 테스트 채널 생성

1. [PortOne 관리자 콘솔](https://admin.portone.io/)에 접속하여 회원가입 및 로그인을 진행합니다.
2. 테스트에 사용할 상점을 준비합니다. 사업자 정보가 없다면 개발 연동 테스트 옵션을 이용합니다.
3. **결제 연동 → 연동 관리 → 채널 관리**에서 새로운 결제 채널을 추가합니다.
4. 카카오페이 테스트 채널을 다음과 같이 설정합니다.

| 항목 | 설정값 |
|---|---|
| 결제대행사 | 카카오페이 |
| 연동 모드 | 테스트 |
| 결제 모듈 | 결제창 일반/정기결제 |
| 채널 이름 | 임의의 식별 가능한 이름 |
| PG 상점아이디(CID) | `TC0ONETIME` |

설정을 저장한 후 생성된 **채널 키(Channel Key)**를 확인합니다.

`TC0ONETIME`은 카카오페이 일반결제 테스트용 CID입니다. PortOne의 Store ID 및 Channel Key와는 서로 다른 식별자이므로 혼동하지 않습니다.

콘솔 화면의 세부 명칭은 변경될 수 있습니다.

공식 안내: [카카오페이 연동 가이드](https://developers.portone.io/opi/ko/integration/pg/v2/kakaopay?v=v2)

#### 8.3. Store ID 및 V2 API Secret 발급

PortOne 관리자 콘솔에서 다음 정보를 확보합니다.

1. **결제 연동 → 연동 관리**에서 Store ID를 확인합니다.
2. **식별코드·API Keys** 메뉴로 이동합니다.
3. V2 API Secret을 발급합니다.
4. 발급받은 API Secret을 안전하게 보관합니다.

| 정보 | 사용 목적 |
|---|---|
| Store ID | 결제가 발생하는 PortOne 상점 식별 |
| Channel Key | 카카오페이 테스트 결제 채널 식별 |
| V2 API Secret | Backend의 결제 조회·취소 API 인증 |
| Webhook Secret | 수신한 Webhook의 서명 검증 |

V2 API Secret은 발급 시점에만 전체 값을 확인할 수 있으므로 분실하지 않도록 주의합니다.

API Secret과 Webhook Secret은 서버에서만 사용하며, 브라우저 또는 GitHub 저장소에 공개하지 않습니다.

또한 **PortOne V1 API Key와 V2 API Secret은 서로 다른 인증 정보**입니다. 반드시 V2용 정보를 사용합니다.

#### 8.4. 로컬 환경변수 설정

프로젝트 루트의 `.env.example`을 참고하여 `.env` 파일에 다음 정보를 설정합니다.

```dotenv
# PortOne V2 공개 결제 설정
PORTONE_STORE_ID=your_portone_store_id
PORTONE_CHANNEL_KEY=your_portone_channel_key

# 서버의 PortOne V2 REST API 인증
PORTONE_API_SECRET=your_portone_api_secret

# Webhook 서명 검증용 (8.7절에서 사용)
PORTONE_WEBHOOK_SECRET=your_portone_webhook_secret

# PortOne V2 REST API Endpoint
PORTONE_API_BASE_URL=https://api.portone.io
```

각 값을 앞서 자신의 PortOne 관리자 콘솔에서 확인한 정보로 변경합니다.

결제창에 필요한 Store ID와 Channel Key는 Backend의 주문 생성 API 응답을 통해 전달됩니다.

따라서 프론트엔드 `.env.local`에 PortOne 인증 정보를 별도로 등록할 필요는 없습니다.

`.env` 파일은 `.gitignore`에 포함되어 있으며 Git 저장소에 커밋하지 않습니다.

#### 8.5. Backend 및 Frontend 실행

설정을 완료한 후 Backend와 Frontend를 실행합니다.

프로젝트 루트에서 Backend를 실행합니다.

```powershell
.\gradlew.bat bootRun
```

다른 터미널에서 Frontend를 실행합니다.

```powershell
cd frontend
npm install
npm run dev
```

기본 실행 주소는 다음과 같습니다.

| 구분 | 주소 |
|---|---|
| Spring Boot | `http://localhost:8080` |
| Vite | `http://localhost:5173` |
| 상점 화면 | `http://localhost:5173/shop` |
| 결제·환불 내역 | `http://localhost:5173/payments` |
| 관리자 환불 관리 | `http://localhost:5173/admin/refunds` |

Vite는 `/api` 요청을 Spring Boot로 프록시합니다.

`frontend/.env.local`에 다음 설정이 존재한다면 제거하거나 `false`로 변경합니다.

```dotenv
VITE_USE_MOCK_API=false
```

환경변수를 변경했다면 개발 서버를 재시작합니다.

**실제 결제 검증은 Mock API가 아닌 Backend와 MySQL을 사용해야 합니다.**

#### 8.6. 카카오페이 테스트 결제 진행

Backend와 Frontend가 정상 실행되었다면 브라우저에서 테스트 결제를 진행합니다.

1. `http://localhost:5173`에 접속합니다.
2. Google 또는 Naver 계정으로 로그인합니다.
3. `/shop` 화면으로 이동합니다.
4. 판매 중인 상품을 선택합니다.
5. 상품명과 가격을 확인한 뒤 **카카오페이로 결제** 버튼을 클릭합니다.
6. 결제 확인창에서 진행을 선택합니다.
7. 카카오페이 테스트 결제창에서 결제를 완료합니다.
8. `/payments` 화면으로 이동하여 구매 내역을 확인합니다.

PC와 모바일의 결제창 호출 방식은 다음과 같습니다.

| 환경 | PortOne 결제창 방식 |
|---|---|
| PC | `IFRAME` |
| 모바일 | `REDIRECTION` |

프로젝트는 결제창 결과만으로 결제를 성공 처리하지 않습니다.

정상 결제 과정에서 다음 REST API가 호출됩니다.

| API | 목적 |
|---|---|
| `POST /api/v1/purchases` | 결제 대기 주문 생성 |
| `POST /api/v1/purchases/{purchaseId}/payment-verifications` | PortOne 최신 결제 상태 검증 |
| `GET /api/v1/purchases` | 구매 내역 조회 |
| `GET /api/v1/purchases/{purchaseId}` | 구매 상세 및 거래 이력 조회 |

결제 완료 후 서버가 PortOne에서 결제 정보를 조회하고, 금액·통화·주문 정보 등을 검증합니다.

정상적으로 검증된 주문만 `PAID` 상태로 확정하고 아이템을 지급합니다.

테스트 결과는 다음 항목을 확인합니다.

- 결제 금액이 DB에 저장된 상품 가격과 일치하는지
- 결제 요청의 `paymentId`와 서버 주문번호가 일치하는지
- 서버 검증 이후에만 구매 완료로 표시되는지
- 구매 내역에 `PAID` 상태가 표시되는지
- `owned_items`에 구매한 아이템이 한 번만 지급되는지
- 취소 또는 실패한 결제를 구매 완료로 표시하지 않는지

**주의:** #93의 로컬 테스트 시드에 포함된 가상 PG 거래는 PortOne에 실제로 존재하는 결제가 아닙니다. 해당 주문을 실제 PG 검증 대상으로 사용하지 않습니다.

#### 8.7. Webhook 설정 (선택, 통합테스트용)

PortOne Webhook은 결제 이벤트를 서버에 전달하는 기능입니다.

결제창을 통한 기본 구매·검증 테스트에는 필수 설정이 아니지만, Webhook 기반 자동 처리와 결과 복구를 검증하려면 추가 설정이 필요합니다.

PortOne 서버에서는 개발자의 `localhost`에 직접 접근할 수 없으므로 외부에서 접근 가능한 HTTPS 주소를 준비합니다.

앞서 [Android 실기기 테스트](#7-android-실기기-실행-및-사진-exif-인증-테스트)에서 사용한 ngrok을 재사용할 수 있습니다.

터미널에서 다음 명령을 실행합니다.

```powershell
ngrok http 5173
```

실행 후 발급받은 HTTPS 주소를 확인합니다.

```text
https://example.ngrok-free.dev
```

이 주소는 예시이므로 실제 발급받은 주소로 변경해야 합니다.

PortOne 관리자 콘솔의 **결제알림(Webhook) 관리**에서 테스트 환경의 Webhook Endpoint를 설정합니다.

1. 상단에서 **V2 테스트** 탭을 선택합니다.
2. **V2 테스트 웹훅 URL**에 ngrok HTTPS 주소와 프로젝트 수신 경로를 합친 Endpoint URL을 입력합니다.
3. Content Type은 `application/json`, 버전은 `2024-04-25`를 선택하고 **저장**을 누릅니다.

| 항목 | 설정값 |
|---|---|
| 결제모듈 | V2 |
| Webhook 버전 | `2024-04-25` |
| 연동 환경 | 테스트 |
| Content-Type | `application/json` |
| Endpoint URL | `https://example.ngrok-free.dev/api/v1/payments/webhooks/portone` |

프로젝트의 실제 Webhook 수신 경로는 다음과 같습니다.

```text
POST /api/v1/payments/webhooks/portone
```

Vite의 `/api` 프록시가 Spring Boot의 `8080` 포트로 요청을 전달하므로, ngrok은 `5173` 포트에 연결할 수 있습니다.

같은 화면의 **V2 테스트 웹훅 시크릿** 영역에서 다음 순서로 발급합니다.

1. **+ 웹훅 시크릿 발급**을 누릅니다.
2. 로컬 테스트용임을 구분할 수 있는 이름을 입력하고 발급합니다.
3. 표시된 Secret을 복사하여 프로젝트 루트 `.env`의 `PORTONE_WEBHOOK_SECRET`에 설정합니다.
4. Secret을 적용하도록 Backend를 재시작합니다.

Endpoint 설정을 저장한 뒤 **호출 테스트**를 실행하여 서버가 `200 OK`를 반환하는지 확인합니다. 저장하지 않은 Endpoint 변경사항은 호출 테스트에 반영되지 않습니다.

Webhook 수신 시 서버는 원본 요청 본문과 서명 Header를 검증합니다.

#### 8.8. 자주 발생하는 문제

| 증상 | 확인할 내용 |
|---|---|
| 결제창이 열리지 않음 | Store ID, Channel Key, 테스트 채널 설정, 브라우저 콘솔 오류 |
| 결제 금액 오류 | 서버의 상품 가격 및 주문 Snapshot |
| PortOne API 인증 오류 | V2 API Secret 및 서버 환경변수 |
| 결제 후 `READY` 유지 | 서버 결제 검증 결과, PortOne 결제 상태, Backend 로그 |
| 결제 후 아이템 미지급 | `PAID` 확정 여부, `owned_items` 테이블 및 서버 오류 |
| 결제 내역이 표시되지 않음 | 로그인 계정, 실제 API 사용 여부, 구매 목록 응답 |
| 모바일 결제 후 복귀 실패 | 복귀 URL, 세션 유지, 동일한 브라우저 및 도메인 |
| Webhook 수신 실패 | ngrok 실행 상태, Endpoint URL, Vite Proxy, 서버 로그 |
| Webhook 서명 오류 | Webhook Secret, 테스트 환경, 원본 요청 및 서명 Header |

테스트 결제 오류가 발생했을 때는 브라우저 개발자 도구의 Network 탭과 Spring Boot 서버 로그를 함께 확인합니다.

결제창에서 성공했다고 표시되더라도 서버 상태가 `PAID`로 확정되지 않았다면 결제가 정상 완료되었다고 판단하지 않습니다.

#### 8.9. 테스트 종료 및 주의사항

- 각 개발자는 가능하면 독립된 PortOne 테스트 상점을 사용합니다.
- 하나의 상점을 공유한다면 결제 내역과 Webhook 설정이 서로 영향을 줄 수 있습니다.
- 테스트 채널과 운영 채널을 혼동하지 않습니다.
- 실제 API Secret과 Webhook Secret을 GitHub, Discord 등에 공개하지 않습니다.
- 테스트용 주문 및 환불은 로컬 DB의 업무 상태를 실제로 변경합니다.
- 결제 승인과 환불 취소 결과는 브라우저 표시뿐 아니라 서버 응답과 DB에서도 확인합니다.
- ngrok을 이용한 테스트가 끝나면 터널을 종료합니다.

공식 문서:

- [PortOne V2 결제 연동 준비](https://developers.portone.io/opi/ko/integration/ready/readme?v=v2)
- [카카오페이 V2 개발 가이드](https://developers.portone.io/opi/ko/integration/pg/v2/kakaopay?v=v2)
- [PortOne V2 Webhook 연동](https://developers.portone.io/opi/ko/integration/webhook/readme-v2)


### 9. Gemini API 그룹 소개 교정 설정

이 프로젝트는 **Google Gemini API**를 이용하여 그룹 소개 문장의 맞춤법과 문법을 교정합니다.

사용자가 그룹 생성 화면에서 직접 교정을 요청하면 Backend가 Gemini API를 호출하고, 교정 결과를 제안합니다. 사용자가 적용을 선택하기 전까지 원문은 변경되지 않습니다.

현재 사용 모델은 `gemini-3.5-flash-lite`이며, API 호출은 Backend에서 처리합니다.

#### 9.1. 준비물

- [Google AI Studio](https://aistudio.google.com/)에 로그인할 Google 계정
- Gemini API Key
- Spring Boot와 Vite가 실행되는 로컬 개발 환경
- Google 또는 Naver 로그인 테스트 계정

Gemini API는 무료 사용 범위를 제공하지만, 모델별 호출 한도와 요금 정책이 다를 수 있습니다. API 사용 전에 [공식 요금 안내](https://ai.google.dev/gemini-api/docs/pricing)를 확인합니다.

#### 9.2. Gemini API Key 발급

1. [Google AI Studio](https://aistudio.google.com/)에 접속하여 Google 계정으로 로그인합니다.
2. **API Keys** 메뉴로 이동합니다.
3. 기존 API Key를 사용하거나 **Create API key**를 선택하여 새로운 키를 발급합니다.
4. 필요한 경우 API Key를 연결할 Google Cloud 프로젝트를 선택하거나 생성합니다.
5. 발급된 API Key를 복사하여 안전하게 보관합니다.

API Key는 외부에 노출되면 다른 사람이 API를 호출하는 데 악용할 수 있으므로 GitHub, Discord 등에 공유하지 않습니다.

공식 안내: [Gemini API Key 관리](https://ai.google.dev/gemini-api/docs/api-key)

#### 9.3. 로컬 환경변수 설정

프로젝트 루트의 `.env.example`을 참고하여 `.env` 파일에 발급받은 API Key를 설정합니다.

```dotenv
GEMINI_API_KEY=your_gemini_api_key
```

`your_gemini_api_key`를 실제 발급받은 값으로 변경합니다.

Backend의 `application.yaml`에는 다음 설정이 이미 정의되어 있습니다.

```yaml
gemini:
  api-key: ${GEMINI_API_KEY:}
```

따라서 별도로 Java 코드나 YAML 파일을 수정할 필요는 없습니다.

API Key는 Backend에서만 사용합니다. `frontend/.env.local`이나 `VITE_`로 시작하는 환경변수에 등록하지 않습니다.

`.env` 파일은 `.gitignore`에 포함되어 있으며 Git 저장소에 커밋하지 않습니다.

#### 9.4. Backend 및 Frontend 실행

환경변수 설정을 완료한 뒤 프로젝트 루트에서 Backend를 실행합니다.

```powershell
.\gradlew.bat bootRun
```

다른 터미널에서 Frontend를 실행합니다.

```powershell
cd frontend
npm install
npm run dev
```

환경변수를 변경했다면 Backend를 재시작해야 합니다.

`frontend/.env.local`에 `VITE_USE_MOCK_API=true`가 설정되어 있다면 제거하거나 `false`로 변경합니다.

**실제 Gemini 연동 검증은 Mock API가 아닌 Backend API를 사용해야 합니다.**

#### 9.5. 그룹 소개 문장 교정 테스트

1. 브라우저에서 `http://localhost:5173`에 접속합니다.
2. Google 또는 Naver 계정으로 로그인합니다.
3. `/groups/select` 화면으로 이동합니다.
4. **+ 새 그룹 생성** 버튼을 클릭합니다.
5. 그룹 소개 입력란에 교정할 문장을 입력합니다.

   예시: `우리 그룹은 매일 같이 성장하는것을 목표로 합니다.`

6. **AI 맞춤형 교정** 버튼을 클릭합니다.
7. AI 교정 제안 영역에 원문과 교정 결과가 각각 표시되는지 확인합니다.
8. **교정 결과 적용** 버튼을 클릭하여 그룹 소개 입력란에 교정 문장이 반영되는지 확인합니다.

교정 결과는 Gemini 응답에 따라 달라질 수 있습니다. 특정 문장으로 정확히 변경되는지보다는 원문과 제안이 구분되어 표시되고 선택 적용이 가능한지 확인합니다.

교정 API의 주요 규칙은 다음과 같습니다.

| 항목 | 조건 |
|---|---|
| API Endpoint | `POST /api/v1/ai/proofreads` |
| 요청 데이터 | `{"text":"교정할 문장"}` |
| 인증 | 로그인 세션 필요 |
| 입력 길이 | 공백 제거 후 1~500자 |
| 사용자별 요청 제한 | 분당 5회, KST 기준 하루 30회 |
| 서버 전체 동시 요청 | 최대 2건 |
| 외부 API Timeout | 10초 |

교정 결과는 자동으로 그룹 소개에 적용되거나 DB에 저장되지 않습니다.

Gemini API를 사용할 수 없는 경우에도 사용자는 원문으로 그룹을 생성할 수 있습니다.

#### 9.6. 자주 발생하는 문제

| 증상 | 확인할 내용 |
|---|---|
| AI 교정 서비스를 사용할 수 없음 (503) | `GEMINI_API_KEY` 설정 여부, API Key 유효성, 모델 접근 권한, Backend 로그 |
| 호출 제한 오류 (429) | 사용자별 분당·일일 제한, 서버 동시 처리 제한, 공급자 호출 한도 |
| 인증 오류 (401) | 로그인 여부 및 세션 만료 |
| CSRF 오류 (403) | 로그인 세션과 CSRF 토큰 상태 |
| 입력값 오류 (400) | 그룹 소개가 비어 있거나 500자를 초과하지 않았는지 |
| 교정 요청이 오래 걸리거나 실패함 | 인터넷 연결, Gemini API 상태, 10초 Timeout |

Gemini API 호출 실패 시 Backend는 `AI_PROVIDER_UNAVAILABLE` 오류를 반환할 수 있습니다.

API Key가 비어 있거나 잘못 설정된 경우에도 같은 오류가 발생할 수 있으므로 `.env` 설정을 먼저 확인합니다.

#### 9.7. 테스트 종료 및 주의사항

- 각 개발자는 가능하면 자신의 Gemini API Key를 발급받아 사용합니다.
- API Key는 Backend에서만 사용하며 브라우저나 GitHub 저장소에 공개하지 않습니다.
- 실제 개인정보나 비밀 정보가 포함된 문장을 테스트용으로 전송하지 않습니다.
- 교정 요청에는 사용자가 입력한 그룹 소개 문장이 외부 Gemini API로 전송됩니다.
- API 이용량과 요금은 Google AI Studio에서 확인합니다.
- 무료 사용 범위 또는 외부 API 장애가 그룹 생성 기능의 가용성에 영향을 주지 않도록 교정 기능을 선택적으로 사용합니다.

공식 문서:

- [Google AI Studio](https://aistudio.google.com/)
- [Gemini API 시작하기](https://ai.google.dev/gemini-api/docs/get-started)
- [Gemini API Key 관리](https://ai.google.dev/gemini-api/docs/api-key)
- [Gemini API 요금 및 무료 사용 범위](https://ai.google.dev/gemini-api/docs/pricing)

