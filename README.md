# 공동 육성 게임 — 다 같이 키우는 생명체

- [프로젝트 설계 문서 보기](docs/index.md)
- [GitHub Projects 보기](https://github.com/heebaek200/shared-creature-game/projects)
- [팀 프로젝트 일일 리포트 보기](https://github.com/heebaek200/shared-creature-game/discussions)
- [일일 리포트 게시글 템플릿](docs/daily-report-template.md)

## 로컬 구동 방법

### 1. 준비물

- Java 21
- MySQL 8
- Node.js 20 이상과 npm

Docker, H2, Flyway와 Liquibase는 사용하지 않습니다. 일반 업무 테이블은 JPA `ddl-auto=update`로 만들고 Spring Session 인프라 테이블은 `db/special-schema.sql`로 초기화합니다. MySQL 생성 컬럼과 UNIQUE 제약은 Entity의 MySQL 전용 Column Definition으로 생성합니다.

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

테스트는 별도 `shared_creature_game_test` 데이터베이스를 사용합니다.

```powershell
.\gradlew.bat test
```

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

## Google OAuth 로컬 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에서 Web Application OAuth Client를 만듭니다.
2. 승인된 JavaScript Origin에 `http://localhost:5173`을 등록합니다.
3. 승인된 Redirect URI에 `.env`의 `GOOGLE_REDIRECT_URI`와 정확히 같은 `http://localhost:5173/oauth2/callback/google`을 등록합니다.

[![Google OAuth 클라이언트 설정 예시](docs/images/google_oauth.png)](https://github.com/heebaek200/shared-creature-game/blob/main/docs/images/google_oauth.png)

4. Client ID와 Client Secret을 각각 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`에 설정합니다.
5. Google Scope는 `openid profile email`만 사용합니다.

[![Google OAuth Scope 설정 예시](docs/images/google_oauth_scope.png)](https://github.com/heebaek200/shared-creature-game/blob/main/docs/images/google_oauth_scope.png)

6. 로그인 시작·`state`·`nonce`·시작 세션 결속과 가입 대기·내부 사용자 연결은 구현되어 있습니다.
7. Backend는 Authorization Code를 Token으로 교환하고 ID Token과 nonce를 검증한 뒤 UserInfo를 조회합니다. 공급자 Token은 Browser나 DB에 저장하지 않습니다.

Google 공식 구현 기준은 [Web Server OAuth 2.0](https://developers.google.com/identity/protocols/oauth2/web-server)과 [OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)를 확인합니다. Naver Adapter는 후속 구현 범위입니다.
