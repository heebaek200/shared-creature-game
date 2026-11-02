# REST API 설계서

## 0. 초보 개발자를 위한 읽는 순서

API 하나를 구현할 때 다음 다섯 곳을 순서대로 확인합니다.

1. **9절 Endpoint 표**에서 Operation ID, Method, Path, 입력 DTO와 응답 DTO를 찾습니다.
2. **10절 요청 필드 표**에서 Path·Query·Header·Body 각 필드의 타입, 필수 여부와 제한을 그대로 구현합니다.
3. **11절 응답 필드 표**에서 `data` 안에 넣을 필드를 확인합니다. 목록이면 `PageMeta`, 랭킹이면 `RankMeta`도 함께 만듭니다.
4. **9.5절**에서 성공 HTTP 상태와 해당 Operation에서 발생할 수 있는 업무 오류를 확인합니다.
5. **2절 공통 계약**대로 성공 Envelope, 오류 Body, `X-Request-Id`와 CSRF를 적용합니다.

백엔드는 Entity나 외부 공급자 응답을 그대로 반환하지 않고 표에 정의된 DTO만 반환합니다. 프론트엔드는 문구가 아닌 `code`와 Enum으로 분기하며, 표에 없는 필드를 임의로 보내거나 사용하지 않습니다.

```javascript
// 목록 API의 가장 단순한 호출 예시입니다.
const response = await fetch('/api/v1/groups?page=0&size=20', {
  credentials: 'include',
  headers: { Accept: 'application/json' },
});
const body = await response.json();
if (!response.ok) throw new Error(body.code);
renderGroups(body.data, body.meta);
```

## 1. 인증 아키텍처

Full CSR이지만 브라우저가 구글·네이버 액세스 토큰을 직접 보관하는 구조는 사용하지 않습니다. 백엔드가 OAuth 2.0 Authorization Code 흐름의 콜백과 토큰 교환을 담당하고, 애플리케이션 로그인 상태는 서버 세션으로 유지합니다.

### 1.1. 로그인 흐름

1. CSR 클라이언트가 백엔드의 공급자별 로그인 시작 API로 이동합니다.
2. 백엔드는 예측 불가능한 일회용 `state`를 생성하고 초대 링크 문맥이 있으면 서버 측 OAuth 시도 상태에 함께 저장합니다.
3. 백엔드는 로그인 시작 익명 세션 ID의 SHA-256 Hash를 OAuth 시도에 저장합니다.
4. 백엔드는 사용자를 구글 또는 네이버의 인가 화면으로 Redirect합니다.
5. 공급자는 인가 코드를 백엔드 콜백 URI로 전달합니다.
6. 백엔드는 `state`와 Callback 세션 Hash를 검증하고 인가 코드를 공급자 토큰으로 서버 간 교환합니다.
7. 백엔드는 공급자 사용자 정보를 조회하고 `provider + provider_user_id`로 기존 회원을 찾습니다.
8. 기존 회원이면 인증 세션을 생성하고, 신규 회원이면 이메일 제공·동일 이메일의 다른 공급자 계정을 검사한 뒤 계정을 생성하지 않고 검증된 소셜 정보와 초대 문맥을 가입 대기 세션에 저장합니다.
9. 백엔드는 세션 ID를 `HttpOnly`, `SameSite=Lax` 쿠키로 설정하고 `integration`·`prod`에서는 `Secure`를 필수로 적용한 뒤 기존 회원은 로그인 완료 경로, 신규 사용자는 `/signup`으로 Redirect합니다. localhost 전용 `local`만 `Secure=false`를 허용합니다.
10. 신규 사용자가 `/signup`에서 닉네임을 제출하면 링크·정원·기존 소속·가입 제한을 다시 검사하고 계정과 가능한 초대 그룹 멤버십을 생성합니다.
11. CSR은 `/api/v1/me`를 호출해 온보딩·그룹 소속 상태에 맞는 화면으로 이동합니다.

### 1.2. 권장 보안 정책

- CSR 정적 자원과 백엔드 API는 동일 사이트에서 제공하고 `/api`, `/ws`, OAuth 콜백을 백엔드로 전달하는 Reverse Proxy 구성을 권장합니다.
- 구글·네이버의 Client Secret과 공급자 토큰은 백엔드에서만 취급합니다.
- 공급자 액세스 토큰과 애플리케이션 인증 토큰을 `localStorage` 또는 `sessionStorage`에 저장하지 않습니다.
- 로그인 완료 후 세션 ID를 교체하여 Session Fixation을 방지합니다.
- `state`는 로그인 시도마다 새로 만들고 짧은 시간 동안 한 번만 사용합니다.
- OAuth Callback은 현재 익명 세션 ID의 Hash가 로그인 시작 시 저장한 `initiating_session_hash`와 일치할 때만 처리합니다. 다른 브라우저·세션이면 공급자 Token 교환 전에 중단합니다.
- Google OpenID Connect를 사용할 때는 ID Token의 서명, 발급자, 대상, 만료와 `nonce`를 검증합니다.
- 쿠키 기반 인증이므로 상태 변경 REST API에는 CSRF 토큰 검증을 적용합니다.
- CORS는 허용된 CSR Origin만 명시하고 Credential 요청에서 와일드카드 Origin을 사용하지 않습니다.
- 공급자 토큰은 로그인 이후 추가 공급자 API 호출이 필요하지 않으면 사용자 정보 조회 후 영구 저장하지 않습니다.
- 서비스 로그아웃은 서버 세션을 무효화합니다. 공급자 로그아웃·연결 해제는 별도 기능으로 구분합니다.
- WebSocket 연결은 기존 애플리케이션 세션으로 인증하고 Handshake의 Origin과 그룹 접근 권한을 서버에서 검증합니다.

### 1.3. 세션 정책

- 세션 저장소는 Spring Session JDBC와 MySQL을 사용합니다.
- 마지막 요청부터 7일 동안 활동이 없으면 세션을 만료합니다.
- 사용자당 활성 세션은 최대 3개입니다.
- 네 번째 로그인에 성공하면 생성 시각이 가장 오래된 기존 세션을 무효화합니다.
- 강퇴, 계정 제한 또는 전체 관리자에 의한 접근 차단이 확정되면 해당 사용자의 활성 세션을 무효화합니다.

### 1.4. 서버 세션을 사용하는 이유

- 브라우저 JavaScript가 장기 인증 토큰을 직접 다루지 않아 XSS 발생 시 토큰 유출 범위를 줄일 수 있습니다.
- 강퇴, 역할 회수와 계정 제한이 발생하면 서버 세션을 무효화하여 이후 요청을 즉시 차단할 수 있습니다.
- 구글·네이버 공급자 토큰과 서비스 내부 인증 수단을 분리할 수 있습니다.
- Full CSR 여부는 화면 렌더링 방식이고 세션 사용 여부와 충돌하지 않습니다. CSR은 쿠키를 포함해 REST API를 호출하고 응답 데이터로 화면을 구성합니다.
- MVP에서는 Spring Session JDBC로 MySQL에 세션을 저장하면 별도 Redis 의존성 없이 다중 인스턴스에서도 세션을 공유할 수 있습니다.

### 1.5. 신규 가입 이메일과 공급자 식별자

- 회원의 외부 식별 키는 이메일이 아닌 `provider + provider_user_id`입니다.
- 신규 가입에는 공급자가 제공한 이메일이 필요합니다. 이메일 동의가 없거나 이메일 값이 없으면 가입을 중단하고 동의가 필요하다는 오류를 반환합니다.
- 기존 회원은 `provider + provider_user_id`가 일치하면 현재 공급자 응답에 이메일이 없어도 로그인합니다.
- 신규 가입 중 같은 이메일의 다른 공급자 회원이 발견되면 신규 계정 생성과 자동 연결을 중단합니다.
- 응답에는 기존 가입 공급자로 로그인해야 한다는 업무 오류 코드를 제공하고 CSR이 안내 화면을 표시합니다.
- 사용자가 명시적으로 계정을 연결하는 기능은 MVP 범위에 포함하지 않습니다.

### 1.6. 공급자별 최소 프로필 정보

신규 가입 이메일과 초기 닉네임 확보에 필요한 최소 정보만 요청합니다.

| 공급자 | 요청·설정 항목 | 사용자 식별자 | 신규 가입 이메일 조건 |
|---|---|---|---|
| Google | `openid profile email` | ID Token의 `sub` | `email` 존재 및 `email_verified = true` |
| Naver | 애플리케이션 프로필 제공 항목 `email`, `nickname` | 프로필 조회 API의 `id` | `email` 존재 |

- 네이버 OAuth 인증 요청에는 별도 `scope` 문자열을 보내지 않고 네이버 개발자센터의 API 권한 설정에서 이메일과 닉네임 제공 항목을 설정합니다.
- 네이버 인가 코드를 토큰으로 교환한 뒤 `/v1/nid/me` 프로필 조회 API에서 `id`, `email`, `nickname`을 조회합니다.
- 공급자 프로필 이름은 최초 닉네임의 기본값으로만 사용하고 사용자가 서비스 안에서 변경할 수 있게 합니다.
- 공급자 프로필 이미지는 사용자 아바타와 목적이 다르므로 MVP에서 저장하지 않습니다.
- 친구 목록, 메시지, 성별, 생년월일과 같은 게임 운영에 불필요한 Scope는 요청하지 않습니다.

### 1.7. 네이버 개발·공개 운영 조건

- 사전 검수 전에는 네이버 개발자센터에 등록한 관리자·테스터 계정만 로그인할 수 있습니다.
- 팀원 4명의 기능 구현과 시연은 해당 계정을 등록해 진행하므로 사전 검수를 MVP 완료 조건으로 두지 않습니다.
- 불특정 사용자가 자신의 네이버 계정으로 로그인할 수 있게 공개 운영하려면 네이버 사전 검수를 통과해야 합니다.
- 공개 배포 환경을 만들더라도 검수 전에는 네이버 로그인 기능이 관리자·테스터에게만 동작한다는 제한을 명시합니다.

## 2. 공통 REST 계약

### 2.1. 경로와 성공 응답

- 업무 REST API의 기본 경로는 `/api/v1`입니다.
- 성공 응답은 `data`와 응답 부가정보 `meta`를 사용합니다.
- 목록 응답의 `meta`에는 `page`, `size`, `totalElements`, `totalPages`를 포함합니다.
- 모든 응답에는 로그와 오류를 연결할 수 있는 `X-Request-Id` Header를 포함합니다.

| 필드 | 타입 | null 가능 | 규칙 |
|---|---|---|---|
| `data` | Operation별 DTO | 아니요 | 9절의 성공 `data` 타입. 빈 응답은 `{}`인 `EmptyResponse` |
| `meta.serverTime` | datetime | 아니요 | 응답 생성 UTC 시각 |
| `meta.page` | int32 | 조건부 | Page 목록에서만 제공, 요청 page와 동일, 0 이상 |
| `meta.size` | int32 | 조건부 | Page 목록에서만 제공, 1~50 |
| `meta.totalElements` | int64 | 조건부 | Page 목록에서만 제공, 전체 행 수, 0 이상 |
| `meta.totalPages` | int32 | 조건부 | Page 목록에서만 제공, 전체 Page 수, 빈 목록이면 0 |
| `meta.currentSubject` | `RankSubjectMeta` | 조건부 | 랭킹 목록에서만 제공 |

Page 필드 네 개는 Page 목록에서 항상 함께 제공하고 일반 단건·일반 목록에서는 모두 생략합니다. 랭킹 목록은 Page 필드와 `currentSubject`를 함께 제공합니다.

| `RankSubjectMeta` 필드 | 타입 | null 가능 | 규칙 |
|---|---|---|---|
| `subjectType` | enum | 아니요 | 개인 랭킹은 `USER`, 그룹 랭킹은 `GROUP` |
| `subjectId` | int64 | 아니요 | 현재 사용자 또는 현재 그룹 ID, 1 이상 |
| `rank` | int32 | 예 | 랭킹 참여 기록이 없으면 `null`, 있으면 1 이상 |
| `score` | int64 | 아니요 | 현재 시즌 기여도 또는 그룹 점수, 미참여면 0 |

```json
{
  "data": {},
  "meta": {
    "serverTime": "2026-11-02T06:38:28Z"
  }
}
```

### 2.2. 오류 응답과 HTTP 상태

```json
{
  "code": "GROUP_FULL",
  "message": "그룹 정원이 마감되었습니다.",
  "fieldErrors": [],
  "traceId": "요청 추적 ID",
  "timestamp": "2026-11-02T06:38:28Z"
}
```

| 필드 | 타입 | null 가능 | 제한·의미 |
|---|---|---|---|
| `code` | string | 아니요 | 8.3절의 불변 오류 코드, 1~100자 |
| `message` | string | 아니요 | 사용자 표시 가능 문구, 1~500자. 프론트 분기에는 사용 금지 |
| `fieldErrors` | `FieldError[]` | 아니요 | 필드 오류가 없으면 빈 배열, 최대 50개 |
| `traceId` | string | 아니요 | 응답 Header `X-Request-Id`와 동일, 1~64자 |
| `timestamp` | datetime | 아니요 | 오류 응답 생성 UTC 시각 |

| `FieldError` 필드 | 타입 | null 가능 | 제한·의미 |
|---|---|---|---|
| `field` | string | 아니요 | DTO 기준 필드 경로, 1~200자. 예: `nickname`, `file` |
| `reason` | enum | 아니요 | `REQUIRED`, `INVALID_FORMAT`, `INVALID_ENUM`, `LENGTH_OUT_OF_RANGE`, `OUT_OF_RANGE`, `FILE_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE` |
| `rejectedValue` | string | 예 | 안전한 값만 최대 100자. Token·파일·개인정보·긴 본문은 `null` |

| HTTP 상태 | 사용 기준 |
|---|---|
| `400 Bad Request` | JSON 형식, 필드와 입력값 검증 실패 |
| `401 Unauthorized` | 로그인 필요 또는 세션 만료 |
| `403 Forbidden` | 존재하는 그룹·관리자 기능의 역할 부족 또는 CSRF 검증 실패 |
| `404 Not Found` | 존재하지 않거나 현재 사용자에게 공개할 수 없는 자원 |
| `409 Conflict` | 정원 마감, 이름 중복, 상태 경합과 멱등 키 오용 |
| `413 Payload Too Large` | 허용한 사진 파일 크기 초과 |
| `415 Unsupported Media Type` | 지원하지 않는 사진 형식 또는 Content Type |
| `429 Too Many Requests` | OAuth·Gemini·사진 제출 호출량 제한 초과 |
| `503 Service Unavailable` | PG·소셜·Gemini 공급자 실패, 외부 호출·사진 처리 포화 또는 Timeout |

내부 예외 클래스명, SQL, Stack Trace와 비밀정보는 응답하지 않습니다. `message`는 사용자에게 표시 가능한 문구이고 분기 로직은 변경 가능한 문구가 아닌 안정적인 `code`를 사용합니다.

### 2.3. CSRF

- `GET /api/v1/auth/csrf`가 현재 세션에 연결된 CSRF Token을 반환합니다.
- CSR은 Token을 메모리에만 보관하고 상태 변경 요청의 `X-CSRF-TOKEN` Header에 전달합니다.
- Token은 `localStorage`와 `sessionStorage`에 저장하지 않습니다.
- 로그인으로 Session ID가 교체되거나 세션이 만료되면 새 Token을 발급받습니다.

### 2.4. 호출량과 처리 자원 제한

- OAuth 시작은 익명 세션당 10분에 5회, IP당 10분에 30회입니다.
- Gemini 교정은 사용자당 1분에 5회, `Asia/Seoul` 날짜당 30회이며 전체 동시 호출은 2건입니다.
- 사진 제출은 사용자당 1분에 5회, 같은 사용자·퀘스트 배정당 판정 제출 이력 5건이며 전체 동시 EXIF 파싱은 4건입니다.
- 횟수 초과는 `429 RATE_LIMIT_EXCEEDED`로 반환하고 `Retry-After` Header에 다시 요청 가능한 초 단위 시간을 넣습니다. 단일 인스턴스 MVP의 메모리 제한값은 애플리케이션 재시작 시 초기화될 수 있습니다.
- Gemini 동시 처리 포화는 `503 AI_BUSY`, 사진 파서 포화는 `503 PHOTO_PROCESSING_BUSY`입니다. 대기열을 무제한으로 늘리지 않습니다.
- IP 제한은 서버가 신뢰하도록 설정한 Proxy가 전달한 주소 또는 직접 연결 주소만 사용하고 임의의 `X-Forwarded-For` Header는 신뢰하지 않습니다.

### 2.5. 멱등성과 요청 추적

- 결제 시작, 환불 요청과 환불 승인처럼 외부 효과가 있는 명령은 UUID 형식의 `Idempotency-Key` Header를 필수로 받습니다.
- 같은 Key와 같은 Payload의 재요청은 최초 처리 결과를 반환합니다.
- 같은 Key를 다른 Payload에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환합니다.
- 같은 사용자·Operation·Key의 최초 요청은 요청 Hash와 함께 `IN_PROGRESS`로 선점하고, 완료 시 HTTP 상태·정제된 응답 Snapshot·결과 대상 ID를 저장합니다.
- 같은 Key·Payload가 아직 `IN_PROGRESS`이면 중복 실행하지 않고 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환합니다. 결제·환불 결과 불명은 기존 상태 조회·정합성 복구 흐름으로 완료합니다.
- 퀘스트 완료는 사용자·배정 UNIQUE를 자연 멱등 키로 사용하고 이미 완료된 요청에는 기존 완료 결과를 반환합니다.
- `X-Request-Id`가 없으면 서버가 생성하며 입력값이 있더라도 허용 형식·길이를 검증합니다.

### 2.6. 시간 표현

- 시각은 ISO 8601 UTC의 `Z` 형식, 업무 날짜는 `YYYY-MM-DD`로 전달합니다.
- KST 업무 경계가 중요한 응답은 서버가 계산한 `restrictionUntil`, `periodStartsAt`, `periodEndsAt`과 기준 시간대 `Asia/Seoul`을 함께 제공합니다.

### 2.7. 인증·온보딩 Endpoint

| Method | 경로 | 인증 | 책임 |
|---|---|---|---|
| `GET` | `/api/v1/auth/csrf` | 공개 세션 | 현재 세션용 CSRF Token 발급 |
| `POST` | `/api/v1/auth/oauth-attempts` | 공개 세션 | 공급자와 현재 초대 문맥을 연결하고 인가 URL 생성 |
| `GET` | `/oauth2/callback/{provider}` | 공급자 Redirect | `state` 검증·인가 코드 교환 후 인가 코드 없는 CSR 경로로 Redirect |
| `GET` | `/api/v1/auth/signup-context` | 가입 대기 세션 | 소셜 공급자·이메일·닉네임 제안과 초대 문맥 조회 |
| `POST` | `/api/v1/auth/signup` | 가입 대기 세션 | 닉네임 확정, 계정 생성과 가능한 경우 초대 그룹 가입 |
| `GET` | `/api/v1/me` | 선택 | 계정·온보딩·그룹·역할·가입 제한과 세션 상태 조회 |
| `POST` | `/api/v1/auth/logout` | 로그인 | 현재 애플리케이션 세션 무효화 |
| `GET` | `/api/v1/invitations/{token}` | 공개 | 초대 그룹명·현재 인원·정원과 링크 상태 조회 |
| `POST` | `/api/v1/invitations/{token}/accept` | 공개 세션 | 초대 문맥을 서버 세션에 저장하고 다음 단계 반환 |
| `POST` | `/api/v1/invitations/{token}/reject` | 공개 세션 | 초대 문맥 제거와 일반 가입 경로 반환 |

- `oauth-attempts` 요청은 `GOOGLE`, `NAVER` 외 공급자를 거절하고 응답의 인가 URL도 허용된 공급자 Host만 사용합니다.
- OAuth Callback은 공통 JSON Envelope 대상이 아닌 브라우저 Redirect Endpoint입니다. 인가 코드·공급자 Token·실패 상세를 CSR URL에 포함하지 않습니다.
- OAuth Callback 성공은 신규·기존 회원 모두 CSR 진입 경로 `/`로 `302` Redirect합니다. CSR은 즉시 `/api/v1/me`를 호출하고 상태 조합에 따라 `/signup`, `/groups/select`, `/creature` 또는 관리자 기본 경로로 이동합니다.
- OAuth Callback 실패는 `/login?oauthError={code}`로만 Redirect합니다. `code` 허용값은 `OAUTH_ACCESS_DENIED`, `OAUTH_STATE_INVALID`, `OAUTH_SESSION_MISMATCH`, `OAUTH_PROVIDER_ERROR`, `OAUTH_PROFILE_INCOMPLETE`, `SOCIAL_EMAIL_ALREADY_REGISTERED`이며 공급자 원문 오류·인가 코드·Token은 전달하지 않습니다.
- `OAuthAttemptRequest.returnRoute`는 `DEFAULT`, `INVITATION`만 허용합니다. `INVITATION`은 서버 세션에 수락한 초대 문맥이 있을 때만 사용하며 임의 URL은 받을 수 없습니다.
- `signup-context`와 가입 대기 세션은 OAuth 시도가 성공한 사용자에게만 제공하고 다른 브라우저에서 재사용할 수 없습니다. 가입 대기 세션은 OAuth 콜백 완료 시점부터 고정 30분 동안 유효하며 조회나 입력 활동으로 만료 시각을 연장하지 않습니다.
- 만료된 가입 대기 세션으로 `signup-context` 또는 `signup`을 요청하면 서버는 대기 정보를 제거하고 `SIGNUP_CONTEXT_EXPIRED`를 반환합니다. 계정은 생성하지 않으며 CSR은 안내 후 `/login`으로 이동시켜 소셜 인증을 다시 시작합니다. 이후 `/api/v1/me`는 `ANONYMOUS + NOT_STARTED`를 반환합니다.
- OAuth Callback은 신규 사용자의 `users`, `social_accounts`, `user_profiles`를 생성하지 않습니다. 이 행들은 `POST /api/v1/auth/signup`에서 닉네임을 확정할 때 생성합니다.
- `POST /api/v1/auth/signup`은 공개 역할 입력을 받지 않습니다. 신규 계정의 공급자와 검증된 이메일이 `INITIAL_SUPER_ADMIN_PROVIDER`, `INITIAL_SUPER_ADMIN_EMAIL`에 모두 정확히 일치할 때만 `SUPER_ADMIN`을 부여하며 이외에는 `USER`로 생성합니다.
- 기존 전체 관리자가 있으면 환경변수는 기존 계정을 승격·강등하지 않습니다. 전체 관리자가 없고 두 환경변수가 모두 준비되지 않은 실행환경은 시작 검증에서 설정 오류로 처리합니다.
- 초대 조건이 유효하면 계정·프로필·소셜 계정·그룹 멤버십을 하나의 트랜잭션으로 생성합니다. 초대 조건이 실패하면 계정 관련 행만 생성하고 실패 이유와 그룹 선택 경로를 반환합니다.
- 초대 수락은 정원을 예약하지 않습니다. 최종 가입 시점에 링크, 정원, 기존 소속과 24시간 제한을 다시 검사합니다.
- 인증된 기존 회원이 그룹 없이 초대를 수락하면 같은 Endpoint가 공통 그룹 가입 트랜잭션을 실행하고 성공 시 `NavigationResponse.nextRoute=/creature`를 반환합니다. 같은 초대 그룹에 이미 가입된 재요청은 기존 성공 이동 결과를 반환합니다.
- 기존 회원의 초대 가입이 정원·소속·24시간 제한 때문에 실패하면 멤버십을 만들지 않고 해당 표준 업무 오류를 반환하며 계정과 현재 세션은 유지합니다.
- `/api/v1/me`는 비로그인·가입 대기·그룹 없음·그룹 회원·전체 관리자 상태를 구분할 수 있는 `authenticationState`, `onboardingState`, `membership`, `roles`, `restrictionUntil`을 반환합니다.
- 상태 조합은 `ANONYMOUS + NOT_STARTED`, `SIGNUP_PENDING + SIGNUP_REQUIRED`, `AUTHENTICATED + GROUP_REQUIRED`, `AUTHENTICATED + COMPLETED`만 허용합니다.
- 로그아웃 성공은 현재 세션만 무효화하고 구글·네이버 계정의 공급자 세션과 연결 상태는 변경하지 않습니다.

## 3. PG 취소 결과 처리

- 현재 Adapter는 포트원 V2의 카카오페이 테스트 채널을 사용하고 전체 취소만 지원합니다.
- PG가 취소 성공을 반환하면 환불을 `COMPLETED`, 결제를 `CANCELLED`, 아이템을 `REVOKED`로 확정합니다.
- PG가 취소 불가를 명확히 반환하면 환불을 `FAILED`로 변경하고 결제와 아이템 소유 상태를 유지합니다.
- Timeout, 연결 단절 또는 해석할 수 없는 응답은 `CANCEL_UNKNOWN`으로 변경합니다.
- `CANCEL_UNKNOWN`에서는 같은 취소를 즉시 다시 호출하지 않고 환불 요청 ID와 PG 거래 ID로 취소 결과 조회 API를 호출합니다.
- 조회 결과 취소 완료면 `COMPLETED`, 미취소면 재검증 후 `CANCEL_PENDING`, 취소 불가면 `FAILED`로 전환합니다.

## 4. 퀘스트 완료 판정 원칙

- `SELF_CHECK` API는 배정 ID만 받고 보상값, 난이도와 완료 횟수를 클라이언트에서 받지 않습니다.
- `SERVER_EVENT`는 서버가 업무 처리 성공 후 생성한 이벤트를 사용해 완료 조건을 판정합니다.
- `AGGREGATE`는 서버 이벤트와 완료 기록을 집계하며 클라이언트가 보낸 누적값을 사용하지 않습니다.
- `PHOTO_EXIF`는 MVP 필수 활성 배정이며 Android Chrome과 iOS Safari 실기기에서 제출·판정 흐름을 검증합니다.
- 이미 완료된 배정의 재요청은 새 보상을 지급하지 않고 기존 완료 결과를 반환합니다.

## 5. 크리처 홈 아바타 Presence 원칙

- 초기 크리처 홈 REST 응답에는 공동 크리처 상태와 현재 접속 사용자별 `userId`, `nickname`, 시즌 기여도·순위, 장착 슬롯별 상품 Snapshot을 포함합니다.
- Presence 영역은 `PRESENCE_JOINED`, `PRESENCE_LEFT`, `AVATAR_EQUIPMENT_CHANGED`만 전달하고 이동 좌표를 전송하지 않습니다.
- `AVATAR_EQUIPMENT_CHANGED`는 장착 트랜잭션 Commit 이후 발행합니다. 환불 회수도 Commit 이후 기본 외형으로 갱신합니다.
- 같은 사용자의 WebSocket 연결 수를 세어 한 아바타만 표시하고 마지막 연결 종료 후 15초 동안 재연결을 기다린 뒤 `PRESENCE_LEFT`를 발행합니다.
- 구독과 Snapshot 조회 시 서버가 현재 활성 그룹을 검사하며 다른 그룹 ID를 임의로 조회·구독할 수 없습니다.
- MVP의 Presence는 단일 애플리케이션 인스턴스 메모리에 두고 DB에 저장하지 않습니다. 다중 인스턴스 배포 전 Redis Presence·Broker 전환이 필요합니다.

### 5.1. WebSocket 목적지와 이벤트

- 연결 Endpoint는 `/ws`, 프로토콜은 Native WebSocket 위의 STOMP를 사용합니다.
- 그룹 실시간 구독 목적지는 `/topic/groups/{groupId}/live` 하나로 통합합니다.
- 클라이언트는 애플리케이션 메시지를 전송하지 않고 STOMP Heartbeat만 사용합니다. 퀘스트 완료, 장착 변경과 같은 명령은 REST API로 처리합니다.
- 구독 시 애플리케이션 세션, Origin과 현재 활성 그룹을 검사하고 탈퇴·강퇴·세션 무효화 후에는 구독을 종료합니다.
- STOMP `CONNECT` Native Header의 `X-CSRF-TOKEN`을 현재 세션의 Token과 비교합니다. 세션·허용 Origin·CSRF 중 하나라도 실패하면 연결을 거부합니다.
- 인바운드 권한은 기본 거부입니다. 인증된 연결의 `DISCONNECT`와 현재 활성 그룹에 대한 정확한 `SUBSCRIBE /topic/groups/{groupId}/live`만 허용하고 클라이언트의 모든 애플리케이션 `SEND`·`MESSAGE` 및 다른 목적지 구독을 거부합니다.

| 이벤트 | 책임 |
|---|---|
| `PRESENCE_JOINED` | 새로 접속한 그룹 구성원과 장착 아바타 추가 |
| `PRESENCE_LEFT` | 15초 재연결 유예가 끝난 구성원 제거 |
| `AVATAR_EQUIPMENT_CHANGED` | 장착·회수 Commit 이후 외형 갱신 |
| `CREATURE_UPDATED` | 경험치·성장 단계·시즌 상태 갱신 |
| `CONTRIBUTION_ADDED` | 최근 기여 활동과 그룹 합계 갱신 |

```json
{
  "eventId": "f573d630-9f66-4a31-a2fa-c3eb15766a6a",
  "eventType": "AVATAR_EQUIPMENT_CHANGED",
  "streamInstanceId": "09a74322-a0a3-4dcb-a4bf-eef6d8a23cce",
  "sequence": 152,
  "groupId": 10,
  "occurredAt": "2026-11-02T07:05:52Z",
  "payload": {}
}
```

`eventId`는 클라이언트 중복 적용 방지, `streamInstanceId`는 서버 실행 변경 감지, `sequence`는 같은 그룹 Stream의 누락 감지에 사용합니다. 업무 이벤트는 DB Commit 이후 발행합니다.

### 5.2. Snapshot과 재동기화

- `GET /api/v1/groups/me/creature-home`은 크리처·기여·접속 아바타 Snapshot과 현재 `streamInstanceId`, `sequence`를 반환합니다.
- 클라이언트는 WebSocket을 먼저 구독하고 도착 이벤트를 임시 보관한 뒤 REST Snapshot을 조회합니다.
- Snapshot 적용 후 같은 `streamInstanceId`에서 Snapshot `sequence`보다 큰 이벤트만 순서대로 반영합니다.
- Sequence 누락, `streamInstanceId` 변경 또는 재연결이 발생하면 Snapshot을 다시 조회합니다.
- WebSocket 연결 실패 중에는 30초 간격으로 Snapshot을 조회하고 연결이 복구되면 Polling을 중단합니다.

## 6. 그룹·시즌·퀘스트·랭킹 Endpoint

### 6.1. 그룹과 멤버십

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `GET` | `/api/v1/groups` | 로그인·그룹 없음 | 공개 그룹명 검색과 페이지 조회 |
| `POST` | `/api/v1/groups` | 로그인·그룹 없음 | 새 그룹 생성과 주 관리자 멤버십 생성 |
| `POST` | `/api/v1/groups/{groupId}/memberships` | 로그인·그룹 없음 | 현재 사용자의 공개 그룹 가입 |
| `GET` | `/api/v1/groups/me` | 그룹 회원 | 현재 그룹 상세 조회 |
| `GET` | `/api/v1/groups/me/members` | 그룹 회원 | 현재 구성원과 역할·마지막 인증 활동 조회 |
| `DELETE` | `/api/v1/groups/me/membership` | 일반·부 관리자 | 현재 사용자의 자진 탈퇴 |
| `PATCH` | `/api/v1/groups/me/members/{userId}/role` | 주 관리자 | 부 관리자 부여·회수 |
| `POST` | `/api/v1/groups/me/members/{userId}/removals` | 주·부 관리자 | 권한별 미접속 강퇴 조건 검사와 제재 이력 생성 |
| `POST` | `/api/v1/groups/me/primary-transfer` | 주 관리자 | 현재 그룹 주 관리자 이전 |
| `GET` | `/api/v1/groups/me/invitation` | 주 관리자 | 현재 초대 링크 조회 |
| `POST` | `/api/v1/groups/me/invitation/rotate` | 주 관리자 | 기존 링크 폐기와 새 링크 발급 |

- 그룹 목록의 기본 `size`는 20, 최대값은 50이며 정규화한 그룹명 검색어를 사용합니다.
- 가입·생성은 현재 소속, 제한 종료 시각과 정원을 같은 트랜잭션 안에서 다시 검사합니다.
- 가입·탈퇴·강퇴·권한 변경·주 관리자 이전은 사용자 ID 오름차순 → 그룹 → 멤버십 ID 오름차순 → 가입 제한의 공통 잠금 순서를 사용합니다.
- 강퇴 요청은 `reason`을 필수로 받고 주·부 관리자에게는 확정된 미접속·역할 조건을 적용합니다. 전체 관리자의 신고 기반 처리는 신고 Endpoint와 연결합니다.
- 미접속 강퇴는 최대 10분의 활동 시각 영속화 지연을 고려해 저장된 `lastActiveAt`에서 72시간 10분이 지난 경우에만 허용하고 강퇴 트랜잭션에서 다시 검사합니다. 화면 문구는 72시간 기준을 사용합니다.
- 주 관리자가 유일한 활성 구성원이면 `leaveMyGroup`이 별도 Endpoint 없이 그룹·초대 링크·현재 시즌 참여를 함께 종료하고 `groupDeactivated=true`를 반환합니다.
- 주 관리자 이전은 대상의 활성 멤버십과 역할을 검사하고 그룹·두 멤버십·변경 이력을 하나의 트랜잭션으로 반영합니다. 대상은 `PRIMARY`, 기존 주 관리자는 `MEMBER`로 변경하며 성공 응답은 변경된 두 역할을 반영합니다.
- 초대 링크 재발급 응답에서만 새 원문 Token을 반환하며 DB에는 Hash만 저장합니다.

### 6.1.1. Gemini 그룹 소개 교정

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `POST` | `/api/v1/ai/proofreads` | 로그인·그룹 없음 | 새 그룹 소개 원문의 한국어 교정 제안 생성 |

- 서버는 `text`만 Gemini `generateContent`에 전달하고 사용자 ID, 이메일, 초대 Token과 세션 정보를 포함하지 않습니다.
- 시스템 지시는 의미·고유명사·사실을 유지한 맞춤법·띄어쓰기·문장 표현 교정으로 고정합니다.
- 응답은 JSON Schema 기반 `correctedText`만 허용하고 서버 검증을 통과한 값만 반환합니다.
- Timeout은 10초이며 자동 재시도하지 않습니다. 실패해도 그룹 생성 원문에는 영향을 주지 않습니다.
- `GEMINI_API_KEY`는 서버 환경변수에서만 읽고 모델명은 `GEMINI_MODEL` 설정으로 교체할 수 있습니다.

### 6.2. 시즌·크리처

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `GET` | `/api/v1/seasons/current` | 로그인 | 현재 시즌의 시작·종료 시각과 상태 조회 |
| `GET` | `/api/v1/groups/me/creature-home` | 그룹 회원 | 크리처·기여·Presence와 Stream Snapshot 조회 |
| `GET` | `/api/v1/creature-species` | 주·부 관리자 | 선택 가능한 활성 크리처 종 목록 조회 |
| `PUT` | `/api/v1/groups/me/seasons/current/creature-selection` | 주·부 관리자 | 부화 전 크리처 종 선택·변경 |

- 크리처 선택은 `creatureSpeciesId`만 받고 서버가 현재 시즌, 선택 권한과 부화 기한을 검사합니다.
- 서비스 공개 시각과 첫 시즌 시작 시각이 같으므로 공개 사용자의 `GET /api/v1/seasons/current`는 항상 활성 시즌을 반환하며 프리시즌 전용 응답은 정의하지 않습니다.
- 같은 종 재선택은 현재 선택 결과를 반환하고 부화 시각 이후 요청은 `409 CREATURE_SELECTION_CLOSED`로 처리합니다.

### 6.3. 퀘스트와 활동

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `GET` | `/api/v1/quests/current?periodType=DAILY\|WEEKLY` | 그룹 회원 | 현재 기간 배정과 사용자 완료 상태 조회 |
| `POST` | `/api/v1/quest-assignments/{assignmentId}/completions` | 그룹 회원 | 사용자 확인형 퀘스트 완료와 보상 반영 |
| `POST` | `/api/v1/check-ins` | 그룹 회원 | KST 날짜별 서비스 체크인 이벤트 생성 |
| `POST` | `/api/v1/photo-submissions` | 그룹 회원 | 필수 EXIF 사진 제출과 검증 |

- 퀘스트 완료 요청은 보상값·난이도·합계값을 받지 않고 배정 Snapshot으로 계산합니다.
- 이미 완료된 배정은 새 보상을 지급하지 않고 기존 완료 결과를 반환합니다.
- 체크인은 서버가 KST 기준일과 오전 시간 여부를 계산하고 날짜별 UNIQUE로 중복 기록을 막습니다.
- 사진 제출은 활성 `PHOTO_EXIF` 배정에만 Multipart 요청을 허용합니다. 서버는 POST 요청 안에서 JPEG·EXIF 판정을 끝내고 외부 응답은 최종 `VALID` 또는 `INVALID`만 반환합니다. DB의 `PENDING`은 파일 저장 후 판정 완료 전까지의 내부 상태이며 API 응답으로 노출하지 않습니다.
- 사용자·퀘스트 배정별 판정 제출은 최대 5건이고 사용자별 사진 요청은 1분에 최대 5회입니다. EXIF APP1 메타데이터는 최대 1 MiB이며 픽셀 전체를 디코딩하지 않습니다.
- EXIF 판정은 최대 4개의 전용 Worker에서 수행하고 5초 안에 끝내야 합니다. Worker 포화·Timeout은 제출 성공으로 간주하지 않고 각각 `PHOTO_PROCESSING_BUSY`, `PHOTO_PROCESSING_TIMEOUT`으로 반환합니다.
- Worker 포화는 제출 행을 만들기 전에 거절합니다. Worker를 얻은 뒤 생성한 제출이 5초를 넘으면 `INVALID`·`PHOTO_PROCESSING_TIMEOUT`으로 기록하고 배정별 5회 시도에 포함한 뒤 `503`을 반환합니다.
- 서버는 GPS 존재·좌표 범위와 촬영 시각이 배정의 `[startsAt, endsAt)`에 포함되는지 검증하고, 서버 업로드 접수 시각도 같은 기간 안이어야 합니다. 판정·보상 트랜잭션이 배정 종료와 시즌 `CLOSING` 전에 Commit되지 않으면 제출 이력만 `INVALID`·`QUEST_PERIOD_ENDED`로 남기고 `409 QUEST_PERIOD_ENDED`를 반환합니다. 마감 유예와 이월은 없습니다.
- EXIF 촬영 시각에 Offset이 없으면 `Asia/Seoul`로 해석합니다. 메타데이터 판정 실패도 제출 이력은 `INVALID`로 저장하고 `failureReason`으로 `EXIF_GPS_MISSING`, `EXIF_GPS_OUT_OF_RANGE`, `EXIF_CAPTURED_AT_MISSING`, `PHOTO_CAPTURED_OUTSIDE_ASSIGNMENT_PERIOD` 중 하나를 반환합니다.

### 6.4. 랭킹

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `GET` | `/api/v1/rankings/members?seasonId={seasonId}` | 그룹 회원 | 현재 그룹 시즌 개인 랭킹 조회 |
| `GET` | `/api/v1/rankings/groups?seasonId={seasonId}` | 로그인 | 시즌 전체 그룹 랭킹 조회 |

- `seasonId`를 생략하면 현재 시즌을 사용하고 종료 시즌은 불변 Snapshot을 조회합니다.
- 페이지 응답에는 현재 사용자 또는 현재 그룹의 순위를 별도 `meta.currentSubject`로 제공합니다.

## 7. 포트원 V2 결제·아바타·환불 API

### 7.1. 공급자 확장 경계

- 도메인과 Application 계층은 `PaymentGateway` Port의 결제 조회·전체 취소 결과만 사용합니다.
- `PortOnePaymentGateway` Adapter가 포트원 V2 REST 요청·응답, 오류와 상태를 내부 표준 결과로 변환합니다.
- Controller와 구매·환불 도메인에는 포트원 SDK DTO와 오류 Enum을 노출하지 않습니다.
- `PaymentGatewayRouter`가 주문의 `provider`로 Adapter를 선택하여 이후 다른 공급자 구현을 추가할 수 있게 합니다.
- `PORTONE_API_SECRET`, `PORTONE_WEBHOOK_SECRET`은 서버 환경변수에서만 읽고 브라우저나 응답에 포함하지 않습니다.
- `PORTONE_STORE_ID`, `PORTONE_CHANNEL_KEY`는 결제창 호출에 필요한 공개 설정만 주문 생성 응답에 포함합니다.
- 결제 채널은 카카오페이로 고정하고 브라우저는 `payMethod=EASY_PAY`, `currency=KRW`를 사용합니다. 카카오페이 채널 자체가 간편결제사이므로 `easyPayProvider`는 전달하지 않습니다.
- `windowType`은 PC `IFRAME`, 모바일 `REDIRECTION`이며 모바일 Redirect URL은 서버가 허용한 CSR 결제 복귀 경로만 사용합니다.

내부 Port의 권장 결과는 공급자와 무관하게 결제 조회의 `PAID`, `READY`, `CANCELLED`, `DEFINITIVE_FAILURE`, `UNKNOWN`과 취소 요청의 `SUCCEEDED`, `REQUESTED`, `DEFINITIVE_FAILURE`, `UNKNOWN`으로 제한하고 공급자 원문 코드는 거래 이력의 정제된 필드에만 저장합니다.

### 7.2. 상점과 아바타

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `GET` | `/api/v1/shop-items` | 로그인 | 판매 상품과 슬롯별 목록 조회 |
| `GET` | `/api/v1/shop-items/{itemId}` | 로그인 | 상품 상세와 과시 카드 Deep Link 조회 |
| `GET` | `/api/v1/owned-items` | 로그인 | 보유·사용·회수 상태별 아이템 조회 |
| `GET` | `/api/v1/avatar` | 로그인 | 현재 슬롯별 장착 상태 조회 |
| `PUT` | `/api/v1/avatar/equipments/{slot}` | 로그인 | 보유 아이템 장착과 최초 사용 확정 |
| `DELETE` | `/api/v1/avatar/equipments/{slot}` | 로그인 | 슬롯 장착 해제 |

최초 장착 요청은 `confirmFirstUse: true`를 요구하고 누락 시 `409 FIRST_USE_CONFIRMATION_REQUIRED`를 반환합니다. 장착·해제 성공 Commit 이후 `AVATAR_EQUIPMENT_CHANGED`를 발행합니다.

### 7.3. 주문과 결제 검증

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `POST` | `/api/v1/purchases` | 로그인 | 상품·가격 Snapshot과 포트원 결제 요청 정보 생성 |
| `POST` | `/api/v1/purchases/{purchaseId}/payment-verifications` | 구매자 | 브라우저 결제 종료 후 포트원 단건 조회·검증 |
| `GET` | `/api/v1/purchases` | 로그인 | 사용자 주문 목록 조회 |
| `GET` | `/api/v1/purchases/{purchaseId}` | 구매자 | 주문·PG 거래·아이템·환불 Timeline 조회 |
| `POST` | `/api/v1/payments/webhooks/portone` | 포트원 공개 호출 | V2 Webhook 검증과 결제 상태 동기화 |

- 주문 생성 응답의 `paymentId`는 내부 `purchase.order_number`와 같고 브라우저는 포트원 V2 `requestPayment()`에 서버가 반환한 `storeId`, 카카오페이 테스트 `channelKey`, 주문명, 금액, `KRW`, `payMethod=EASY_PAY`와 Redirect URL을 전달합니다.
- 브라우저 반환값이나 Redirect Query는 결제 성공의 근거가 아닙니다. `payment-verifications`는 `paymentId`만 받고 서버가 포트원 단건 조회 API로 상태·Store ID·금액·통화를 내부 주문과 비교합니다.
- 브라우저 완료와 Webhook은 같은 `PaymentReconciliationService`를 호출하고 행 잠금·상태 조건으로 어느 쪽이 먼저 도착해도 한 번만 `PAID`와 아이템 지급을 확정합니다.
- Webhook은 `2024-04-25` JSON 형식과 Standard Webhooks Signature를 검증하고, 검증 후에도 `paymentId`로 포트원 최신 결제를 조회합니다.
- 내부 주문번호 `paymentId`는 `purchases.order_number`에만 유지하고 승인 거래 이력의 공급자 식별자는 포트원 거래 객체 ID를 저장합니다.
- 알 수 없는 Webhook Event·필드는 실패시키지 않고 무시하며, 같은 Webhook ID는 UNIQUE로 중복 처리를 막습니다.
- 같은 Webhook ID가 `PROCESSED`, `IGNORED`이거나 처리 시작 후 5분 미만의 `PENDING`이면 중복 처리 없이 `202`를 반환합니다. 5분 이상 지난 `PENDING`과 `FAILED`는 `PAYMENT_RECONCILIATION`이 PortOne 최신 결제를 조회해 같은 `PaymentReconciliationService`로 복구합니다.
- Webhook Endpoint는 CSRF 검사 대상에서 제외하지만 Signature 검증 전에는 어떤 업무 상태도 변경하지 않습니다.

### 7.4. 환불과 포트원 취소

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `POST` | `/api/v1/purchases/{purchaseId}/refund-requests` | 구매자 | 미사용 아이템 전체 환불 요청 |
| `GET` | `/api/v1/refund-requests` | 로그인 | 사용자 환불 요청 내역 |
| `GET` | `/api/v1/admin/refund-requests` | 전체 관리자 | 처리 상태별 환불 목록 |
| `GET` | `/api/v1/admin/refund-requests/{requestId}` | 전체 관리자 | 주문·사용·PG 거래 포함 요청 상세 |
| `POST` | `/api/v1/admin/refund-requests/{requestId}/approvals` | 전체 관리자 | 재검증·선점 후 포트원 전체 취소 |
| `POST` | `/api/v1/admin/refund-requests/{requestId}/rejections` | 전체 관리자 | 거절 사유 확정 |
| `POST` | `/api/v1/admin/refund-requests/{requestId}/reconciliations` | 전체 관리자 | 결과 불명 결제 단건 조회와 복구 |

- 취소 Adapter는 포트원 V2 `POST /payments/{paymentId}/cancel`을 사용하고 동일 취소 재시도에는 같은 포트원 `Idempotency-Key`를 전달합니다.
- 포트원 취소 응답의 `SUCCEEDED`는 내부 성공, `FAILED`는 명확한 실패, `REQUESTED`는 비동기 처리 중으로 변환합니다. `REQUESTED`에서는 환불을 `CANCEL_PENDING`으로 유지하고 Webhook 또는 최신 결제 조회 전에는 `COMPLETED`·아이템 회수를 확정하지 않습니다.
- 취소 성공 응답의 `cancellation.id`를 취소 거래 행의 공급자 식별자로 저장하고, 승인 거래의 공급자 식별자와 혼용하지 않습니다.
- 포트원의 멱등 보장 기간과 별개로 애플리케이션 DB의 환불 상태·시도 이력이 장기 중복 취소를 차단합니다.
- 포트원 외부 호출의 읽기 Timeout은 최소 60초로 설정하고 Timeout·연결 단절은 성공·실패로 추정하지 않고 `CANCEL_UNKNOWN`으로 처리합니다.
- 결과 불명 상태에서는 포트원 결제 단건 조회로 `CANCELLED` 여부를 확인한 뒤에만 재시도 여부를 결정합니다.
- `cancel_claimed_at`부터 5분 이상 지난 `CANCEL_PENDING`은 5분 주기 복구 작업이 PortOne 조회부터 수행합니다. 취소 완료는 `COMPLETED`, 미취소는 기존 멱등 키 재시도, 취소 불가는 `FAILED`, 조회 결과 불명은 `CANCEL_UNKNOWN`으로 확정합니다.

### 7.5. 신고와 전체 관리자

| Method | 경로 | 권한 | 책임 |
|---|---|---|---|
| `POST` | `/api/v1/reports` | 그룹 회원 | 같은 그룹 사용자 신고 접수 |
| `GET` | `/api/v1/admin/reports` | 전체 관리자 | 신고 목록 조회 |
| `GET` | `/api/v1/admin/reports/{reportId}` | 전체 관리자 | 신고·대상·처리 이력 상세 조회 |
| `POST` | `/api/v1/admin/reports/{reportId}/dismissals` | 전체 관리자 | 신고 기각과 사유 기록 |
| `POST` | `/api/v1/admin/reports/{reportId}/removals` | 전체 관리자 | 신고 기반 강퇴와 제한 생성 |
| `POST` | `/api/v1/admin/groups/{groupId}/primary-transfer` | 전체 관리자 | 마지막 관리자 보호·복구를 위한 특정 그룹 주 관리자 재지정 |
| `GET` | `/api/v1/admin/dashboard` | 전체 관리자 | 필터별 요약·Google Charts 집계 조회 |
| `GET` | `/api/v1/admin/operations/warnings` | 전체 관리자 | Scheduler·정합성·결과 불명 경고 조회 |

### 7.6. 공식 연동 기준

- [포트원 V2 인증 결제 연동](https://developers.portone.io/opi/ko/integration/start/v2/checkout)
- [포트원 V2 카카오페이 연동](https://developers.portone.io/opi/ko/integration/pg/v2/kakaopay)
- [포트원 V2 REST API](https://developers.portone.io/api/rest-v2)
- [포트원 V2 Webhook](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2)
- [포트원 V2 결제 취소](https://developers.portone.io/opi/ko/integration/cancel/v2/readme)
- [Gemini Generate Content API](https://ai.google.dev/api/generate-content)
- [Gemini Structured Outputs](https://ai.google.dev/gemini-api/docs/structured-output)

## 8. 명령 DTO·응답 DTO·업무 오류 카탈로그

### 8.1. 주요 명령 DTO

| DTO | 필드 | 검증·의미 |
|---|---|---|
| `CreateGroupRequest` | `name`, `description` | 이름은 NFKC·Trim 후 2~30자, 소개는 선택 0~500자 |
| `ProofreadTextRequest` | `text` | Trim 후 1~500자 그룹 소개 원문 |
| `ChangeMemberRoleRequest` | `role` | `MEMBER`, `DEPUTY`만 허용 |
| `RemoveMemberRequest` | `reason` | 필수, 1~500자 |
| `TransferPrimaryRequest` | `targetUserId`, `reason` | 활성 회원 ID. 전체 관리자 재지정은 사유 필수 |
| `SelectCreatureRequest` | `creatureSpeciesId` | 활성 크리처 종류 ID |
| `CreatePurchaseRequest` | `shopItemId` | 판매 중인 상품 ID |
| `VerifyPaymentRequest` | `paymentId` | 내부 주문 번호와 일치해야 함 |
| `EquipAvatarItemRequest` | `ownedItemId`, `confirmFirstUse` | 보유 아이템과 최초 사용 명시 확인 |
| `CreateRefundRequest` | `reason` | 필수, 1~500자 |
| `ApproveRefundRequest` | `decisionReason` | 관리자 승인 근거, 1~500자 |
| `RejectRefundRequest` | `decisionReason` | 관리자 거절 사유, 1~500자 |

퀘스트 완료와 체크인은 클라이언트 계산값을 받지 않으므로 Request Body를 사용하지 않습니다. 모든 문자열 길이는 정규화 이후 서버에서 다시 검증합니다.

### 8.2. 주요 응답 DTO

| DTO | 핵심 정보 |
|---|---|
| `CurrentUserResponse` | 인증·온보딩 상태, 사용자, 그룹 멤버십, 역할, 가입 제한 종료 시각 |
| `GroupSummaryResponse` | 그룹명, 현재 인원, 정원, 남은 자리와 가입 가능 여부 |
| `CreatureHomeResponse` | 시즌·크리처·경험치·최근 기여·Presence·Stream Snapshot |
| `QuestAssignmentResponse` | 배정 Snapshot, 기간, 보상, 검증 방식과 사용자 완료 상태 |
| `AvatarResponse` | 슬롯별 장착 아이템과 최초 사용 상태 |
| `PurchaseCheckoutResponse` | 내부 주문 ID, 포트원 결제 요청용 공개 설정과 Snapshot 금액 |
| `PurchaseTimelineResponse` | 주문·PG 거래·보유 아이템·환불의 현재 상태와 시각 순서 |
| `AdminDashboardResponse` | 필터, 집계 시각, 요약 지표, 차트별 데이터와 최근 관리 Queue |
| `ProofreadTextResponse` | 사용자가 선택적으로 적용할 교정 문장 |

응답은 Entity와 공급자 DTO를 직접 반환하지 않습니다. 금액은 원 단위 정수, 시각은 UTC ISO 8601, 역할·상태는 확정된 문자열 Enum으로 제공합니다.

### 8.3. 업무 오류 코드

| HTTP | 코드 | 의미 |
|---|---|---|
| `400` | `INVALID_REQUEST`, `INVALID_FIELD_VALUE` | 필수값·형식·길이·허용 Enum 검증 실패 |
| `400` | `SLOT_MISMATCH` | 상품 슬롯과 요청 슬롯 불일치 |
| `401` | `AUTH_REQUIRED`, `SESSION_EXPIRED` | 로그인 필요·세션 만료 |
| `403` | `CSRF_INVALID`, `ROLE_FORBIDDEN` | CSRF 또는 역할 권한 부족 |
| `404` | `RESOURCE_NOT_FOUND` | 존재하지 않거나 요청자에게 노출할 수 없는 리소스 |
| `409` | `NICKNAME_ALREADY_USED`, `GROUP_NAME_ALREADY_USED` | 정규화한 이름 중복 |
| `409` | `SIGNUP_CONTEXT_EXPIRED` | OAuth 콜백 완료 후 30분이 지나 가입 대기 문맥 만료. 대기 정보를 제거하고 `/login`에서 재인증 필요 |
| `409` | `GROUP_FULL`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE`, `INVITE_INVALID` | 그룹 가입 조건 충돌 |
| `409` | `INACTIVITY_REQUIREMENT_NOT_MET`, `PRIMARY_TRANSFER_REQUIRED` | 강퇴·주 관리자 보호 조건 미충족 |
| `409` | `CREATURE_SELECTION_CLOSED`, `QUEST_PERIOD_ENDED`, `QUEST_COMPLETION_NOT_ALLOWED` | 시즌·퀘스트 수행 불가 또는 검증 방식이 맞지 않음 |
| `409` | `PHOTO_ATTEMPT_LIMIT_EXCEEDED` | 같은 사용자·퀘스트 배정의 판정 제출 5회 소진 |
| `409` | `FIRST_USE_CONFIRMATION_REQUIRED` | 최초 사용 확인 필요 |
| `409` | `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_PENDING`, `PAYMENT_RESULT_UNKNOWN` | 결제 검증·확정 불가 |
| `409` | `REFUND_NOT_ALLOWED_USED_ITEM`, `REFUND_ALREADY_ACTIVE`, `PG_CANCEL_RESULT_UNKNOWN` | 환불 조건·결과 충돌 |
| `409` | `CONCURRENT_MODIFICATION`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 동시 수정, 멱등 키 오용 또는 동일 요청 처리 중 |
| `413` | `FILE_TOO_LARGE` | 사진 파일 10 MiB 초과 |
| `413` | `PHOTO_METADATA_TOO_LARGE` | EXIF APP1 메타데이터 1 MiB 초과 |
| `415` | `PHOTO_FORMAT_UNSUPPORTED` | JPEG가 아니거나 MIME 형식과 JPEG Signature가 일치하지 않는 사진. HEIC·HEIF 포함 |
| `429` | `RATE_LIMIT_EXCEEDED` | OAuth·Gemini·사진 제출의 사용자·세션·IP 호출량 제한 초과 |
| `503` | `PAYMENT_PROVIDER_UNAVAILABLE` | 포트원 일시 장애 |
| `503` | `OAUTH_PROVIDER_UNAVAILABLE` | Google·Naver 인가 또는 프로필 조회 일시 장애 |
| `503` | `AI_PROOFREAD_UNAVAILABLE` | Gemini Timeout·안전 차단·응답 형식 오류 또는 일시 장애 |
| `503` | `AI_BUSY` | Gemini 서버 전체 동시 호출 2건 초과 |
| `503` | `PHOTO_PROCESSING_BUSY`, `PHOTO_PROCESSING_TIMEOUT` | EXIF 전용 Worker 4건 포화 또는 5초 처리시간 초과 |

- 같은 업무 오류는 Endpoint와 화면에 관계없이 같은 HTTP 상태와 의미를 유지합니다.
- `code`는 프론트엔드 분기용 불변 식별자이고 `message`는 사용자에게 표시 가능한 변경 가능 문구입니다.
- Bean Validation 또는 명시적 `validate()`에서 발견한 입력 형식 오류는 `400`과 `fieldErrors`로 반환하며 업무 오류와 구분합니다.
- 이미 완료된 퀘스트의 동일 요청은 오류가 아니라 기존 성공 결과를 반환합니다.

## 9. Endpoint별 요청·응답 계약

Operation ID는 구현 메서드명과 API 테스트 식별자로 사용합니다. `요청 ID`는 [사용자 시나리오 및 업무 규칙](business-rules.md#0-요청-식별-번호와-추적-기준)의 추적 번호입니다. `-`는 Request Body가 없다는 뜻이며, 모든 JSON 요청은 `Content-Type: application/json`을 사용합니다.

요청 형식은 아래 네 가지뿐입니다. 브라우저와 백엔드가 서로 다른 형식으로 해석하지 않도록 Operation 표의 입력 위치를 그대로 따릅니다.

| 요청 종류 | `Content-Type` | Body 작성 방법 |
|---|---|---|
| 일반 JSON 명령 | `application/json` | 10.2절 Request DTO와 같은 필드명으로 JSON 작성 |
| 사진 제출 | 브라우저가 생성한 `multipart/form-data; boundary=...` | `FormData`에 `assignmentId`, `file` 추가. `Content-Type` 직접 지정 금지 |
| PortOne Webhook | PortOne이 전송한 `application/json` | 브라우저가 호출하지 않으며 원문 Body와 서명 Header를 서버가 검증 |
| GET·DELETE Body 없음 | 없음 | Path·Query·Header만 사용하고 빈 JSON `{}`도 보내지 않음 |

모든 브라우저 요청은 `Accept: application/json`, `credentials: 'include'`를 사용합니다. 상태 변경 요청은 `getCsrfToken`으로 받은 값을 `X-CSRF-TOKEN`에 넣되 OAuth Callback과 PortOne Webhook에는 넣지 않습니다.

### 9.1. 인증·초대

| Operation ID | 요청 ID | Method·경로 | 입력 | 성공 `data` |
|---|---|---|---|---|
| `getCsrfToken` | `REQ-AUTH-004` | `GET /api/v1/auth/csrf` | - | `CsrfResponse` |
| `createOAuthAttempt` | `REQ-AUTH-001`, `REQ-INV-001` | `POST /api/v1/auth/oauth-attempts` | Body `OAuthAttemptRequest` | `OAuthAttemptResponse` |
| `handleOAuthCallback` | `REQ-AUTH-001` | `GET /oauth2/callback/{provider}` | Path `provider`, Query `code`, `state`, `error` | 허용된 CSR 경로로 `302` Redirect |
| `getSignupContext` | `REQ-AUTH-002`, `REQ-AUTH-003` | `GET /api/v1/auth/signup-context` | - | `SignupContextResponse` |
| `completeSignup` | `REQ-AUTH-002`, `REQ-INV-001` | `POST /api/v1/auth/signup` | Body `SignupRequest` | `CurrentUserResponse` |
| `getCurrentUser` | `REQ-AUTH-004` | `GET /api/v1/me` | - | `CurrentUserResponse` |
| `logout` | `REQ-AUTH-004` | `POST /api/v1/auth/logout` | - | `EmptyResponse` |
| `getPublicInvitation` | `REQ-INV-001` | `GET /api/v1/invitations/{token}` | Path `token` | `PublicInvitationResponse` |
| `acceptInvitation` | `REQ-INV-001` | `POST /api/v1/invitations/{token}/accept` | Path `token` | `NavigationResponse` |
| `rejectInvitation` | `REQ-INV-001` | `POST /api/v1/invitations/{token}/reject` | Path `token` | `NavigationResponse` |

### 9.2. 그룹·시즌·퀘스트·랭킹

| Operation ID | 요청 ID | Method·경로 | 입력 | 성공 `data` |
|---|---|---|---|---|
| `listPublicGroups` | `REQ-GRP-001` | `GET /api/v1/groups` | Query `PublicGroupsQuery` | `GroupSummaryResponse[]` + Page `meta` |
| `createGroup` | `REQ-GRP-001` | `POST /api/v1/groups` | Body `CreateGroupRequest` | `CreateGroupResponse` |
| `proofreadGroupDescription` | `REQ-AI-001` | `POST /api/v1/ai/proofreads` | Body `ProofreadTextRequest` | `ProofreadTextResponse` |
| `joinGroup` | `REQ-GRP-001`, `REQ-GRP-002` | `POST /api/v1/groups/{groupId}/memberships` | Path `groupId` | `MembershipResponse` |
| `getMyGroup` | `REQ-GRP-002`, `REQ-GRP-003` | `GET /api/v1/groups/me` | - | `GroupDetailResponse` |
| `listMyGroupMembers` | `REQ-GRP-003`, `REQ-GRP-004`, `REQ-GRP-005` | `GET /api/v1/groups/me/members` | Query `GroupMembersQuery` | `GroupMemberResponse[]` + Page `meta` |
| `leaveMyGroup` | `REQ-GRP-003` | `DELETE /api/v1/groups/me/membership` | - | `MembershipExitResponse` |
| `changeMemberRole` | `REQ-GRP-004` | `PATCH /api/v1/groups/me/members/{userId}/role` | Path `userId`, Body `ChangeMemberRoleRequest` | `GroupMemberResponse` |
| `removeGroupMember` | `REQ-GRP-005` | `POST /api/v1/groups/me/members/{userId}/removals` | Path `userId`, Body `RemoveMemberRequest` | `MembershipExitResponse` |
| `transferPrimaryAdmin` | `REQ-GRP-004` | `POST /api/v1/groups/me/primary-transfer` | Body `TransferPrimaryRequest` | `GroupDetailResponse` |
| `getInvitationManagement` | `REQ-INV-002` | `GET /api/v1/groups/me/invitation` | - | `InvitationManagementResponse` |
| `rotateInvitation` | `REQ-INV-002` | `POST /api/v1/groups/me/invitation/rotate` | - | `InvitationRotatedResponse` |
| `getCurrentSeason` | `REQ-SEA-001` | `GET /api/v1/seasons/current` | - | `SeasonResponse` |
| `getCreatureHome` | `REQ-SEA-001`, `REQ-SEA-002`, `REQ-SEA-003`, `REQ-AVA-002`, `REQ-RT-001` | `GET /api/v1/groups/me/creature-home` | - | `CreatureHomeResponse` |
| `listActiveCreatureSpecies` | `REQ-SEA-002` | `GET /api/v1/creature-species` | - | `CreatureSpeciesResponse[]` |
| `selectCreatureSpecies` | `REQ-SEA-002` | `PUT /api/v1/groups/me/seasons/current/creature-selection` | Body `SelectCreatureRequest` | `CreatureSelectionResponse` |
| `listCurrentQuests` | `REQ-QST-001`, `REQ-QST-002` | `GET /api/v1/quests/current` | Query `CurrentQuestsQuery` | `QuestAssignmentResponse[]` |
| `completeQuestAssignment` | `REQ-QST-002` | `POST /api/v1/quest-assignments/{assignmentId}/completions` | Path `assignmentId` | `QuestCompletionResponse` |
| `createCheckIn` | `REQ-QST-003` | `POST /api/v1/check-ins` | - | `CheckInResponse` |
| `submitQuestPhoto` | `REQ-QST-004` | `POST /api/v1/photo-submissions` | Multipart `assignmentId`, `file` | `PhotoSubmissionResponse` |
| `getMemberRanking` | `REQ-RNK-001` | `GET /api/v1/rankings/members` | Query `MemberRankingQuery` | `MemberRankResponse[]` + Rank `meta` |
| `getGroupRanking` | `REQ-RNK-002` | `GET /api/v1/rankings/groups` | Query `GroupRankingQuery` | `GroupRankResponse[]` + Rank `meta` |

### 9.3. 상점·아바타·결제·환불

| Operation ID | 요청 ID | Method·경로 | 입력 | 성공 `data` |
|---|---|---|---|---|
| `listShopItems` | `REQ-AVA-001` | `GET /api/v1/shop-items` | Query `ShopItemsQuery` | `ShopItemResponse[]` + Page `meta` |
| `getShopItem` | `REQ-AVA-001` | `GET /api/v1/shop-items/{itemId}` | Path `itemId` | `ShopItemResponse` |
| `listOwnedItems` | `REQ-AVA-001` | `GET /api/v1/owned-items` | Query `OwnedItemsQuery` | `OwnedItemResponse[]` + Page `meta` |
| `getAvatar` | `REQ-AVA-001` | `GET /api/v1/avatar` | - | `AvatarResponse` |
| `equipAvatarItem` | `REQ-AVA-001` | `PUT /api/v1/avatar/equipments/{slot}` | Path `slot`, Body `EquipAvatarItemRequest` | `AvatarResponse` |
| `unequipAvatarItem` | `REQ-AVA-001` | `DELETE /api/v1/avatar/equipments/{slot}` | Path `slot` | `AvatarResponse` |
| `createPurchase` | `REQ-PAY-001` | `POST /api/v1/purchases` | Header `Idempotency-Key`, Body `CreatePurchaseRequest` | `PurchaseCheckoutResponse` |
| `verifyPurchasePayment` | `REQ-PAY-001` | `POST /api/v1/purchases/{purchaseId}/payment-verifications` | Path `purchaseId`, Body `VerifyPaymentRequest` | `PurchaseTimelineResponse` |
| `listPurchases` | `REQ-PAY-001`, `REQ-PAY-002` | `GET /api/v1/purchases` | Query `PurchasesQuery` | `PurchaseSummaryResponse[]` + Page `meta` |
| `getPurchase` | `REQ-PAY-001`, `REQ-PAY-002` | `GET /api/v1/purchases/{purchaseId}` | Path `purchaseId` | `PurchaseTimelineResponse` |
| `receivePortOneWebhook` | `REQ-PAY-001`, `REQ-PAY-002` | `POST /api/v1/payments/webhooks/portone` | PortOne 서명 Header와 V2 Webhook Body | `202 Accepted`, `EmptyResponse` |
| `createRefundRequest` | `REQ-PAY-002` | `POST /api/v1/purchases/{purchaseId}/refund-requests` | Header `Idempotency-Key`, Path `purchaseId`, Body `CreateRefundRequest` | `RefundResponse` |
| `listMyRefundRequests` | `REQ-PAY-002` | `GET /api/v1/refund-requests` | Query `RefundRequestsQuery` | `RefundResponse[]` + Page `meta` |
| `listAdminRefundRequests` | `REQ-ADM-003` | `GET /api/v1/admin/refund-requests` | Query `RefundRequestsQuery` | `RefundResponse[]` + Page `meta` |
| `getAdminRefundRequest` | `REQ-ADM-003` | `GET /api/v1/admin/refund-requests/{requestId}` | Path `requestId` | `RefundDetailResponse` |
| `approveRefund` | `REQ-ADM-003` | `POST /api/v1/admin/refund-requests/{requestId}/approvals` | Header `Idempotency-Key`, Path `requestId`, Body `ApproveRefundRequest` | `RefundDetailResponse` |
| `rejectRefund` | `REQ-ADM-003` | `POST /api/v1/admin/refund-requests/{requestId}/rejections` | Path `requestId`, Body `RejectRefundRequest` | `RefundDetailResponse` |
| `reconcileRefund` | `REQ-ADM-003` | `POST /api/v1/admin/refund-requests/{requestId}/reconciliations` | Header `Idempotency-Key`, Path `requestId` | `RefundDetailResponse` |

### 9.4. 신고·전체 관리자

| Operation ID | 요청 ID | Method·경로 | 입력 | 성공 `data` |
|---|---|---|---|---|
| `createUserReport` | `REQ-GRP-006` | `POST /api/v1/reports` | Body `CreateReportRequest` | `ReportResponse` |
| `listAdminReports` | `REQ-ADM-002` | `GET /api/v1/admin/reports` | Query `AdminReportsQuery` | `ReportResponse[]` + Page `meta` |
| `getAdminReport` | `REQ-ADM-002` | `GET /api/v1/admin/reports/{reportId}` | Path `reportId` | `ReportDetailResponse` |
| `dismissReport` | `REQ-ADM-002` | `POST /api/v1/admin/reports/{reportId}/dismissals` | Path `reportId`, Body `DismissReportRequest` | `ReportDetailResponse` |
| `removeReportedMember` | `REQ-GRP-006`, `REQ-ADM-002` | `POST /api/v1/admin/reports/{reportId}/removals` | Path `reportId`, Body `RemoveReportedMemberRequest` | `ReportDetailResponse` |
| `transferPrimaryAdminAsSuperAdmin` | `REQ-GRP-004`, `REQ-ADM-004` | `POST /api/v1/admin/groups/{groupId}/primary-transfer` | Path `groupId`, Body `TransferPrimaryRequest` | `GroupDetailResponse` |
| `getAdminDashboard` | `REQ-ADM-001` | `GET /api/v1/admin/dashboard` | Query `AdminDashboardQuery` | `AdminDashboardResponse` |
| `listOperationalWarnings` | `REQ-ADM-004` | `GET /api/v1/admin/operations/warnings` | Query `OperationalWarningsQuery` | `OperationalWarningResponse[]` + Page `meta` |

### 9.5. Operation별 성공 상태와 오류

#### 성공 HTTP 상태

| HTTP | Operation ID |
|---|---|
| `302 Found` | `handleOAuthCallback` |
| `202 Accepted` | `receivePortOneWebhook` |
| `201 Created` | `createOAuthAttempt`, `completeSignup`, `createGroup`, `joinGroup`, `createPurchase`, `createRefundRequest`, `createUserReport` |
| `200 OK` | 위 Operation을 제외한 9절의 모든 Operation |

`DELETE`도 결과 DTO를 반환하므로 `204`를 사용하지 않습니다. 멱등 재요청은 최초 성공 응답의 HTTP 상태와 Body를 그대로 반환합니다.

#### 모든 Operation에 적용하는 공통 오류

- JSON·Query·Path·Header 형식이 틀리면 `400 INVALID_REQUEST` 또는 `400 INVALID_FIELD_VALUE`입니다.
- 로그인 또는 가입 대기 세션이 필요한데 없으면 `401 AUTH_REQUIRED`, 만료됐으면 `401 SESSION_EXPIRED`입니다.
- 역할이 부족하면 `403 ROLE_FORBIDDEN`, 상태 변경 요청의 CSRF가 틀리면 `403 CSRF_INVALID`입니다.
- 다른 사용자의 구매·보유 아이템·환불처럼 소유권이 없는 개별 자원은 존재 여부를 숨기고 `404 RESOURCE_NOT_FOUND`를 반환합니다. 존재하는 그룹 관리·전체 관리자 기능에서 역할만 부족하면 `403 ROLE_FORBIDDEN`입니다.
- `Idempotency-Key` 명령은 Key 재사용·처리 중 상태에 따라 `409 IDEMPOTENCY_KEY_REUSED`, `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`를 사용합니다.

아래 표는 공통 오류 외에 Operation별로 추가되는 업무 오류입니다. 표에 없는 읽기 Operation은 공통 오류만 사용합니다.

| Operation ID | 추가 오류 코드 |
|---|---|
| `createOAuthAttempt` | `429 RATE_LIMIT_EXCEEDED`; `503 OAUTH_PROVIDER_UNAVAILABLE` |
| `handleOAuthCallback` | JSON 오류 대신 2.7절의 `oauthError` 코드로 `/login` Redirect. 시작 세션 불일치는 `OAUTH_SESSION_MISMATCH` |
| `getSignupContext`, `completeSignup` | `409 SIGNUP_CONTEXT_EXPIRED`; 가입 완료는 `409 NICKNAME_ALREADY_USED` 추가 |
| `getPublicInvitation` | 잘못되거나 폐기된 Token은 `404 RESOURCE_NOT_FOUND` |
| `acceptInvitation` | `409 INVITE_INVALID`, `GROUP_FULL`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE` |
| `createGroup` | `409 GROUP_NAME_ALREADY_USED`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE` |
| `joinGroup` | `409 GROUP_FULL`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE` |
| `leaveMyGroup` | 활성 구성원이 2명 이상인 마지막 주 관리자는 `409 PRIMARY_TRANSFER_REQUIRED`. 주 관리자가 유일한 구성원이면 성공과 함께 그룹 비활성화 |
| `changeMemberRole`, `transferPrimaryAdmin`, `transferPrimaryAdminAsSuperAdmin` | `409 PRIMARY_TRANSFER_REQUIRED`, `CONCURRENT_MODIFICATION` |
| `removeGroupMember` | `409 INACTIVITY_REQUIREMENT_NOT_MET`, `PRIMARY_TRANSFER_REQUIRED`, `CONCURRENT_MODIFICATION` |
| `rotateInvitation` | 동시 재발급은 `409 CONCURRENT_MODIFICATION` |
| `proofreadGroupDescription` | `429 RATE_LIMIT_EXCEEDED`; `503 AI_BUSY`, `AI_PROOFREAD_UNAVAILABLE` |
| `selectCreatureSpecies` | `409 CREATURE_SELECTION_CLOSED`, `CONCURRENT_MODIFICATION` |
| `completeQuestAssignment` | `409 QUEST_PERIOD_ENDED`, `QUEST_COMPLETION_NOT_ALLOWED`, `CONCURRENT_MODIFICATION` |
| `createCheckIn` | 같은 KST 날짜 재요청은 오류가 아니라 기존 `CheckInResponse` 반환 |
| `submitQuestPhoto` | `409 QUEST_PERIOD_ENDED`, `QUEST_COMPLETION_NOT_ALLOWED`, `PHOTO_ATTEMPT_LIMIT_EXCEEDED`; `413 FILE_TOO_LARGE`, `PHOTO_METADATA_TOO_LARGE`; `415 PHOTO_FORMAT_UNSUPPORTED`; `429 RATE_LIMIT_EXCEEDED`; `503 PHOTO_PROCESSING_BUSY`, `PHOTO_PROCESSING_TIMEOUT`. EXIF 내용 실패는 `200`과 `status=INVALID` |
| `equipAvatarItem` | `404 RESOURCE_NOT_FOUND`; `400 SLOT_MISMATCH`; `409 FIRST_USE_CONFIRMATION_REQUIRED`, `CONCURRENT_MODIFICATION` |
| `unequipAvatarItem` | `409 CONCURRENT_MODIFICATION` |
| `createPurchase` | 판매 중이 아닌 상품은 `404 RESOURCE_NOT_FOUND`; `503 PAYMENT_PROVIDER_UNAVAILABLE` |
| `verifyPurchasePayment` | `409 PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_PENDING`, `PAYMENT_RESULT_UNKNOWN`; `503 PAYMENT_PROVIDER_UNAVAILABLE` |
| `receivePortOneWebhook` | 서명·형식 실패는 `400` 빈 Body, 정상 수신은 처리 결과와 무관하게 `202` |
| `createRefundRequest` | `409 REFUND_NOT_ALLOWED_USED_ITEM`, `REFUND_ALREADY_ACTIVE` |
| `approveRefund`, `reconcileRefund` | `409 REFUND_NOT_ALLOWED_USED_ITEM`, `REFUND_ALREADY_ACTIVE`, `PG_CANCEL_RESULT_UNKNOWN`, `CONCURRENT_MODIFICATION`; `503 PAYMENT_PROVIDER_UNAVAILABLE` |
| `rejectRefund` | `409 CONCURRENT_MODIFICATION` |
| `dismissReport`, `removeReportedMember` | 이미 처리됐으면 `409 CONCURRENT_MODIFICATION` |
| `removeReportedMember` | 마지막 주 관리자 대상이면 `409 PRIMARY_TRANSFER_REQUIRED` |

## 10. 요청 필드 상세 정의

### 10.1. 공통 Path·Query·Header

| 위치·필드 | 타입 | 필수 | 제한과 정규화 |
|---|---|---|---|
| `*Id` Path·Query | `int64` | 경로는 필수 | 1 이상, Java `Long` 범위. 소유권·노출 권한이 없으면 존재 여부를 숨기는 `404` 사용 |
| `token` Path | `string` | 필수 | URL-safe Base64 문자 `[A-Za-z0-9_-]`, 32~128자. 원문을 로그에 남기지 않음 |
| `provider` Path | `enum` | OAuth Callback 필수 | 소문자로 `google`, `naver`만 허용 |
| `code` Callback Query | `string` | 성공 Callback 필수 | 1~2048자, 공급자 인가 코드. 로그·Redirect URL에 재노출 금지 |
| `state` Callback Query | `string` | 필수 | URL-safe 문자 32~128자, 저장된 Hash와 상수 시간 비교 |
| `error` Callback Query | `string` | 실패 Callback 선택 | 최대 100자 공급자 오류 코드. 사용자용 표준 코드로 변환 |
| `page` | `int32` | 선택 | 0 이상, 기본 0 |
| `size` | `int32` | 선택 | 1~50, 기본 20 |
| `q` | `string` | 선택 | NFKC·Trim 후 0~30자. 빈 문자열은 검색 조건 없음 |
| `periodType` | `enum` | 필수 | `DAILY`, `WEEKLY` |
| `seasonId` | `int64` | 선택 | 생략하면 현재 시즌, 1 이상 |
| `slot` Path | `enum` | 장착·해제 API 필수 | `HEAD`, `FACE`, `BODY`, `ACCESSORY` |
| `range` | `enum` | 선택 | `7_DAYS`, `30_DAYS`, `CURRENT_SEASON`; 기본 `7_DAYS` |
| `Idempotency-Key` | UUID 문자열 | 해당 명령 필수 | RFC 4122 UUID, 최대 36자. 같은 Key·Payload만 재사용 가능 |
| `X-CSRF-TOKEN` | `string` | 상태 변경 요청 필수 | 현재 세션에서 발급된 Token과 일치. Webhook 제외 |
| `X-Request-Id` | `string` | 선택 | 영문·숫자·`-`·`_`, 1~64자. 없으면 서버 생성 |

알 수 없는 Query 값과 같은 Query의 중복·쉼표 구분 입력은 무시하지 않고 `400 INVALID_FIELD_VALUE`로 반환합니다.

#### 10.1.1. Endpoint별 Query DTO

| Query DTO.field | 타입 | 필수 | 기본값·허용값 | 고정 정렬 |
|---|---|---|---|---|
| `PublicGroupsQuery.q` | string | 선택 | NFKC·Trim 후 0~30자, 빈 값은 전체 | - |
| `PublicGroupsQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `PublicGroupsQuery.size` | int32 | 선택 | 기본 20, 1~50 | `name ASC, groupId ASC` |
| `GroupMembersQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `GroupMembersQuery.size` | int32 | 선택 | 기본 20, 1~50 | `role PRIMARY→DEPUTY→MEMBER, joinedAt ASC, userId ASC` |
| `CurrentQuestsQuery.periodType` | enum | 필수 | `DAILY`, `WEEKLY` | `slotNumber ASC` |
| `MemberRankingQuery.seasonId` | int64 | 선택 | 생략 시 현재 시즌, 1 이상 | - |
| `MemberRankingQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `MemberRankingQuery.size` | int32 | 선택 | 기본 20, 1~50 | `rank ASC` |
| `GroupRankingQuery.seasonId` | int64 | 선택 | 생략 시 현재 시즌, 1 이상 | - |
| `GroupRankingQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `GroupRankingQuery.size` | int32 | 선택 | 기본 20, 1~50 | `rank ASC` |
| `ShopItemsQuery.slot` | enum | 선택 | `HEAD`, `FACE`, `BODY`, `ACCESSORY`; 생략 시 전체 | - |
| `ShopItemsQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `ShopItemsQuery.size` | int32 | 선택 | 기본 20, 1~50 | `displayOrder ASC, itemId ASC` |
| `OwnedItemsQuery.status` | enum | 선택 | `OWNED_UNUSED`, `OWNED_USED`, `REVOKED`; 생략 시 전체 | - |
| `OwnedItemsQuery.slot` | enum | 선택 | 아바타 슬롯, 생략 시 전체 | - |
| `OwnedItemsQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `OwnedItemsQuery.size` | int32 | 선택 | 기본 20, 1~50 | `ownedItemId DESC` |
| `PurchasesQuery.status` | enum | 선택 | `READY`, `PAID`, `FAILED`, `CANCELLED`; 생략 시 전체 | - |
| `PurchasesQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `PurchasesQuery.size` | int32 | 선택 | 기본 20, 1~50 | `createdAt DESC, purchaseId DESC` |
| `RefundRequestsQuery.status` | enum | 선택 | `REQUESTED`, `REJECTED`, `CANCEL_PENDING`, `CANCEL_UNKNOWN`, `COMPLETED`, `FAILED`; 생략 시 전체 | - |
| `RefundRequestsQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `RefundRequestsQuery.size` | int32 | 선택 | 기본 20, 1~50 | `requestedAt DESC, refundRequestId DESC` |
| `AdminReportsQuery.status` | enum | 선택 | `RECEIVED`, `RESOLVED`, `REJECTED`; 생략 시 전체 | - |
| `AdminReportsQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `AdminReportsQuery.size` | int32 | 선택 | 기본 20, 1~50 | `createdAt DESC, reportId DESC` |
| `AdminDashboardQuery.range` | enum | 선택 | `7_DAYS`, `30_DAYS`, `CURRENT_SEASON`; 기본 `7_DAYS` | KST 날짜 오름차순 |
| `AdminDashboardQuery.groupId` | int64 | 선택 | 생략 시 전체 그룹, 지정 시 1 이상이며 존재하는 그룹 | - |
| `OperationalWarningsQuery.type` | enum | 선택 | `SCHEDULER_FAILED`, `QUEST_TEMPLATE_SHORTAGE`, `CAPACITY_MISMATCH`, `PAYMENT_UNKNOWN`, `REFUND_UNKNOWN`, `PHOTO_DELETE_FAILED`; 생략 시 전체 | - |
| `OperationalWarningsQuery.page` | int32 | 선택 | 기본 0, 0 이상 | - |
| `OperationalWarningsQuery.size` | int32 | 선택 | 기본 20, 1~50 | `detectedAt DESC, warningId ASC` |

`listMyRefundRequests`와 `listAdminRefundRequests`는 같은 `RefundRequestsQuery`를 사용합니다. 정렬용 필드는 클라이언트 입력으로 받지 않고 표의 고정 정렬만 사용합니다.

### 10.2. 인증·그룹 요청 Body

| DTO.field | 타입 | 필수 | 제한과 의미 |
|---|---|---|---|
| `OAuthAttemptRequest.provider` | `enum` | 필수 | `GOOGLE`, `NAVER` |
| `OAuthAttemptRequest.returnRoute` | `enum` | 선택 | `DEFAULT`, `INVITATION`; 기본 `DEFAULT`. URL 원문 불가, `INVITATION`은 서버 초대 문맥이 있을 때만 허용 |
| `SignupRequest.nickname` | `string` | 필수 | NFKC·Trim 후 2~20자, 제어문자 금지, 정규화 닉네임 전역 UNIQUE |
| `CreateGroupRequest.name` | `string` | 필수 | NFKC·Trim 후 2~30자, 제어문자 금지, 정규화 그룹명 전역 UNIQUE |
| `CreateGroupRequest.description` | `string` | 선택 | Trim 후 0~500자, 제어문자 금지. Gemini 결과도 동일 검증 |
| `ProofreadTextRequest.text` | `string` | 필수 | Trim 후 1~500자, 제어문자 금지. 그룹 소개 교정 이외 용도 금지 |
| `ChangeMemberRoleRequest.role` | `enum` | 필수 | `MEMBER`, `DEPUTY`. `PRIMARY` 이전에는 사용 불가 |
| `RemoveMemberRequest.reason` | `string` | 필수 | Trim 후 1~500자 |
| `TransferPrimaryRequest.targetUserId` | `int64` | 필수 | 1 이상, 현재 그룹의 활성 일반·부 관리자 |
| `TransferPrimaryRequest.reason` | `string` | 조건부 필수 | 주 관리자 직접 이전은 선택·최대 500자, 전체 관리자 재지정은 Trim 후 1~500자 |
| `SelectCreatureRequest.creatureSpeciesId` | `int64` | 필수 | 1 이상, 활성 종이며 부화 기한 전이어야 함 |

초대 수락·거절, 그룹 가입·탈퇴와 링크 재발급은 클라이언트 계산값을 받지 않으므로 Body가 없습니다. 가입 대상 그룹과 사용자는 Path 및 인증 세션에서 결정합니다.

### 10.3. 퀘스트·상품·결제·관리 요청 Body

| DTO.field | 타입 | 필수 | 제한과 의미 |
|---|---|---|---|
| `PhotoSubmission.assignmentId` | `int64` | 필수 | Multipart 숫자 필드, 1 이상, 현재 사용자의 활성 사진 배정 |
| `PhotoSubmission.file` | binary | 필수 | Multipart `file`, 최대 10 MiB. `image/jpeg`이면서 JPEG SOI Signature `FF D8 FF`가 일치해야 함. HEIC·HEIF 포함 다른 형식은 거절 |
| `CreatePurchaseRequest.shopItemId` | `int64` | 필수 | 1 이상, 판매 중인 상품. 가격은 서버 Snapshot 사용 |
| `VerifyPaymentRequest.paymentId` | `string` | 필수 | 1~100자, 서버가 발급한 주문 번호와 정확히 일치 |
| `EquipAvatarItemRequest.ownedItemId` | `int64` | 필수 | 1 이상, 현재 사용자의 `OWNED_UNUSED` 또는 `OWNED_USED` 아이템 |
| `EquipAvatarItemRequest.confirmFirstUse` | `boolean` | 조건부 필수 | `usedAt = null`이면 반드시 `true`; 재장착은 `false` 허용 |
| `CreateRefundRequest.reason` | `string` | 필수 | Trim 후 1~500자 |
| `ApproveRefundRequest.decisionReason` | `string` | 필수 | Trim 후 1~500자, 감사 이력에 저장 |
| `RejectRefundRequest.decisionReason` | `string` | 필수 | Trim 후 1~500자, 사용자에게 표시 가능해야 함 |
| `CreateReportRequest.targetUserId` | `int64` | 필수 | 1 이상, 신고자와 같은 활성 그룹의 다른 사용자 |
| `CreateReportRequest.category` | `enum` | 필수 | `HARASSMENT`, `ABUSE`, `INAPPROPRIATE_CONTENT`, `OTHER` |
| `CreateReportRequest.content` | `string` | 필수 | Trim 후 10~1000자 |
| `DismissReportRequest.reason` | `string` | 필수 | Trim 후 1~1000자 |
| `RemoveReportedMemberRequest.reason` | `string` | 필수 | Trim 후 1~1000자, 신고 내용과 별도로 최종 강퇴 근거 기록 |

퀘스트 완료와 체크인은 Body가 없습니다. 경험치, 기여도, 완료 시각, 그룹 ID와 누적 횟수는 서버가 배정 Snapshot·세션·서버 시각으로 계산합니다.

### 10.4. Webhook 입력

- PortOne Webhook은 공급자의 `2024-04-25` V2 JSON 형식을 그대로 수신하며 아래 필드만 읽습니다. 알 수 없는 추가 필드는 무시합니다.
- Standard Webhooks의 `webhook-id`, `webhook-timestamp`, `webhook-signature` Header가 모두 필요합니다. 각 값은 비어 있을 수 없고 최대 500자이며 원문 Body와 함께 PortOne JVM SDK 또는 Standard Webhooks 호환 검증기로 검증합니다.
- 서명 검증 전 Body 최대 크기는 256 KiB로 제한하고 Content Type은 `application/json`만 허용합니다.
- 허용 시각 편차와 재전송 검증 후 `paymentId`를 추출하며, 알 수 없는 Event Type은 `202`로 수신 처리하되 업무 상태를 변경하지 않습니다.

| Body 필드 | 타입 | 필수 | 제한·처리 |
|---|---|---|---|
| `type` | string | 필수 | 최대 100자. 처리 대상은 `Transaction.Ready`, `Transaction.Paid`, `Transaction.Cancelled`, `Transaction.Failed`, `Transaction.PayPending`, `Transaction.CancelPending`; 다른 값은 상태 변경 없이 `202` |
| `timestamp` | datetime | 필수 | RFC 3339. 재전송되어도 같은 이벤트 시각 |
| `data.storeId` | string | 필수 | 설정된 `PORTONE_STORE_ID`와 일치해야 함, 최대 100자 |
| `data.paymentId` | string | 결제 이벤트 필수 | 내부 `purchases.order_number`와 연결, 1~100자 |
| `data.transactionId` | string | 결제 이벤트 필수 | PortOne 결제 시도 ID, 1~255자 |
| `data.cancellationId` | string | 취소 이벤트 조건부 | 취소 관련 Event에서만 제공, 1~255자 |

서명 검증 실패는 JSON 오류 Body 대신 `400` 빈 Body를 반환합니다. 서명은 유효하지만 알 수 없는 Event이거나 내부 주문이 없으면 재전송을 막기 위해 `202`와 `EmptyResponse`를 반환하고 업무 상태는 변경하지 않습니다. 알려진 결제 Event는 `paymentId`로 PortOne 결제 단건 조회를 수행한 뒤 같은 `202` 응답을 반환합니다.

## 11. 응답 필드 상세 정의

모든 응답 DTO는 공통 성공 Envelope의 `data` 안에 들어갑니다. 표의 `null 가능`은 JSON 필드를 생략한다는 뜻이 아니라 값이 `null`일 수 있다는 뜻입니다. 목록은 빈 배열 `[]`로 반환하고 `null` 목록을 사용하지 않습니다.

### 11.1. 공통·인증·초대 응답

| DTO.field | 타입 | null 가능 | 의미·허용값 |
|---|---|---|---|
| `EmptyResponse` | object | 아니요 | 필드가 없는 `{}` |
| `CsrfResponse.headerName` | string | 아니요 | 항상 `X-CSRF-TOKEN` |
| `CsrfResponse.token` | string | 아니요 | 현재 세션 전용 Token. 저장소에 영구 저장 금지 |
| `OAuthAttemptResponse.authorizationUrl` | string | 아니요 | Google 또는 Naver의 검증된 HTTPS 인가 URL |
| `OAuthAttemptResponse.expiresAt` | datetime | 아니요 | OAuth 시도 만료 UTC 시각, 생성 후 10분 |
| `SignupContextResponse.provider` | enum | 아니요 | `GOOGLE`, `NAVER` |
| `SignupContextResponse.email` | string | 아니요 | 공급자가 제공한 가입 이메일, 최대 320자 |
| `SignupContextResponse.suggestedNickname` | string | 예 | 공급자 닉네임을 20자 이내로 정리한 제안값 |
| `SignupContextResponse.invitation` | `PublicInvitationResponse` | 예 | 초대 문맥이 있을 때만 제공 |
| `SignupContextResponse.expiresAt` | datetime | 아니요 | 가입 대기 세션 만료 UTC 시각, OAuth 콜백 완료 후 30분 |
| `CurrentUserResponse.authenticationState` | enum | 아니요 | `ANONYMOUS`, `SIGNUP_PENDING`, `AUTHENTICATED` |
| `CurrentUserResponse.onboardingState` | enum | 아니요 | `NOT_STARTED`, `SIGNUP_REQUIRED`, `GROUP_REQUIRED`, `COMPLETED` |
| `CurrentUserResponse.user` | `UserSummaryResponse` | 예 | 계정 생성 전에는 `null` |
| `CurrentUserResponse.membership` | `MembershipResponse` | 예 | 활성 그룹이 없으면 `null` |
| `CurrentUserResponse.roles` | string[] | 아니요 | `SUPER_ADMIN`, `PRIMARY`, `DEPUTY`, `MEMBER` 중 현재 역할 |
| `CurrentUserResponse.restrictionUntil` | datetime | 예 | 활성 24시간 가입 제한 종료 UTC 시각 |
| `CurrentUserResponse.sessionNotice` | enum | 예 | 가장 오래된 세션 종료 시 `OLDEST_SESSION_EVICTED` |
| `CurrentUserResponse.onboardingReason` | enum | 예 | 초대 가입 실패로 그룹 선택이 필요하면 `INVITE_INVALID`, `GROUP_FULL`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE`; 그 외 `null` |
| `UserSummaryResponse.userId` | int64 | 아니요 | 내부 사용자 ID |
| `UserSummaryResponse.nickname` | string | 아니요 | 2~20자 표시 닉네임 |
| `UserSummaryResponse.provider` | enum | 아니요 | 현재 로그인 공급자 `GOOGLE`, `NAVER` |
| `UserSummaryResponse.email` | string | 아니요 | 가입 당시 이메일 Snapshot, 최대 320자 |
| `PublicInvitationResponse.groupId` | int64 | 아니요 | 초대 대상 그룹 ID |
| `PublicInvitationResponse.groupName` | string | 아니요 | 2~30자 그룹명 |
| `PublicInvitationResponse.currentMemberCount` | int32 | 아니요 | 0~20 |
| `PublicInvitationResponse.capacity` | int32 | 아니요 | MVP에서 20 |
| `PublicInvitationResponse.status` | enum | 아니요 | `ACTIVE`, `INVALIDATED`, `GROUP_UNAVAILABLE` |
| `PublicInvitationResponse.acceptable` | boolean | 아니요 | 현재 링크 자체를 수락할 수 있는지 여부. 자리 예약 의미 아님 |
| `NavigationResponse.nextRoute` | enum | 아니요 | `/login`, `/signup`, `/groups/select`, `/creature` 중 하나 |
| `NavigationResponse.reasonCode` | enum | 예 | 초대 거절은 `INVITATION_REJECTED`, 정상 수락·이동은 `null`; 가입 업무 실패는 오류 응답 또는 `CurrentUserResponse.onboardingReason` 사용 |

### 11.2. 그룹 응답

| DTO.field | 타입 | null 가능 | 의미·허용값 |
|---|---|---|---|
| `GroupSummaryResponse.groupId` | int64 | 아니요 | 그룹 ID |
| `GroupSummaryResponse.name` | string | 아니요 | 2~30자 그룹명 |
| `GroupSummaryResponse.description` | string | 아니요 | 그룹 소개, 미입력 시 빈 문자열, 최대 500자 |
| `GroupSummaryResponse.currentMemberCount` | int32 | 아니요 | 0~20 |
| `GroupSummaryResponse.capacity` | int32 | 아니요 | 20 |
| `GroupSummaryResponse.remainingCapacity` | int32 | 아니요 | `capacity - currentMemberCount`, 0~20 |
| `GroupSummaryResponse.joinable` | boolean | 아니요 | 응답 생성 시점의 가입 가능 표시값 |
| `GroupSummaryResponse.joinBlockedReason` | enum | 예 | `FULL`, `ALREADY_IN_GROUP`, `COOLDOWN`; 명령 시 서버가 재검사 |
| `GroupDetailResponse.group` | `GroupSummaryResponse` | 아니요 | 현재 그룹 요약 |
| `GroupDetailResponse.primaryAdmin` | `GroupMemberResponse` | 아니요 | 현재 주 관리자 |
| `GroupDetailResponse.myMembership` | `MembershipResponse` | 아니요 | 요청자의 활성 멤버십 |
| `CreateGroupResponse.group` | `GroupDetailResponse` | 아니요 | 생성된 그룹과 주 관리자 멤버십 |
| `CreateGroupResponse.initialInvitationUrl` | string | 아니요 | 자동 생성된 링크. 응답에서 한 번만 제공하며 서버에는 Token Hash만 저장 |
| `ProofreadTextResponse.correctedText` | string | 아니요 | 사용자가 선택적으로 적용할 교정 제안, 1~500자 |
| `MembershipResponse.membershipId` | int64 | 아니요 | 멤버십 이력 ID |
| `MembershipResponse.groupId` | int64 | 아니요 | 활성 그룹 ID |
| `MembershipResponse.role` | enum | 아니요 | `PRIMARY`, `DEPUTY`, `MEMBER` |
| `MembershipResponse.joinedAt` | datetime | 아니요 | 가입 UTC 시각 |
| `MembershipResponse.status` | enum | 아니요 | 활성 응답에서는 `ACTIVE` |
| `GroupMemberResponse.userId` | int64 | 아니요 | 구성원 사용자 ID |
| `GroupMemberResponse.nickname` | string | 아니요 | 현재 표시 닉네임, 2~20자 |
| `GroupMemberResponse.role` | enum | 아니요 | `PRIMARY`, `DEPUTY`, `MEMBER` |
| `GroupMemberResponse.joinedAt` | datetime | 아니요 | 현재 멤버십 가입 시각 |
| `GroupMemberResponse.lastActiveAt` | datetime | 예 | 마지막 로그인 또는 인증 API 요청 성공 UTC 시각, 활동 이력이 없으면 `null` |
| `GroupMemberResponse.inactiveHours` | int64 | 아니요 | 서버 기준 경과 시간, 0 이상 |
| `GroupMemberResponse.availableActions` | string[] | 아니요 | 요청자가 실행 가능한 `GRANT_DEPUTY`, `REVOKE_DEPUTY`, `REMOVE`, `TRANSFER_PRIMARY` |
| `MembershipExitResponse.membershipId` | int64 | 아니요 | 종료된 멤버십 ID |
| `MembershipExitResponse.exitType` | enum | 아니요 | `LEFT`, `REMOVED_INACTIVE`, `REMOVED_REPORT` |
| `MembershipExitResponse.exitedAt` | datetime | 아니요 | 종료 UTC 시각 |
| `MembershipExitResponse.restrictionUntil` | datetime | 아니요 | 가입 가능 UTC 시각, 종료 후 24시간 |
| `MembershipExitResponse.groupDeactivated` | boolean | 아니요 | 1인 주 관리자 탈퇴로 그룹까지 `INACTIVE`가 됐으면 `true`, 일반 탈퇴·강퇴는 `false` |
| `InvitationManagementResponse.active` | boolean | 아니요 | 활성 링크 존재 여부 |
| `InvitationManagementResponse.issuedAt` | datetime | 예 | 활성 링크 발급 시각 |
| `InvitationManagementResponse.copyable` | boolean | 아니요 | Hash만 저장하므로 조회 응답은 항상 `false` |
| `InvitationRotatedResponse.invitationUrl` | string | 아니요 | 새 원문 Token이 포함된 URL, 이 응답에서 한 번만 제공 |
| `InvitationRotatedResponse.issuedAt` | datetime | 아니요 | 재발급 UTC 시각 |
| `InvitationRotatedResponse.previousInvalidated` | boolean | 아니요 | 기존 링크 무효화 완료 여부, 성공 응답은 `true` |

### 11.3. 시즌·크리처·퀘스트·랭킹 응답

| DTO.field | 타입 | null 가능 | 의미·허용값 |
|---|---|---|---|
| `SeasonResponse.seasonId` | int64 | 아니요 | 시즌 ID |
| `SeasonResponse.sequence` | int32 | 아니요 | 서비스 첫 시즌부터 증가하는 시즌 번호 |
| `SeasonResponse.startsAt` | datetime | 아니요 | KST 월요일 0시에 대응하는 UTC 시각 |
| `SeasonResponse.endsAt` | datetime | 아니요 | 시작 후 4주 |
| `SeasonResponse.status` | enum | 아니요 | `SCHEDULED`, `ACTIVE`, `CLOSING`, `ENDED` |
| `CreatureSpeciesResponse.speciesId` | int64 | 아니요 | 활성 크리처 종 ID |
| `CreatureSpeciesResponse.code` | string | 아니요 | 개발자 정의 불변 코드, 1~50자 |
| `CreatureSpeciesResponse.name` | string | 아니요 | 화면 표시명, 1~50자 |
| `CreatureSpeciesResponse.assetKey` | string | 아니요 | 프론트엔드 이미지·애니메이션 리소스 식별자, 최대 255자 |
| `CreatureSpeciesResponse.displayOrder` | int32 | 아니요 | 오름차순 표시 순서, 0 이상 |
| `CreatureSelectionResponse.speciesId` | int64 | 아니요 | 선택한 종 ID |
| `CreatureSelectionResponse.speciesName` | string | 아니요 | 표시명, 1~50자 |
| `CreatureSelectionResponse.selectionSource` | enum | 아니요 | `ADMIN`, `RANDOM` |
| `CreatureSelectionResponse.selectedAt` | datetime | 아니요 | 선택 확정 시각 |
| `CreatureSelectionResponse.changeableUntil` | datetime | 아니요 | 부화 기한 |
| `CreatureHomeResponse.groupId` | int64 | 아니요 | 현재 그룹 ID |
| `CreatureHomeResponse.season` | `SeasonResponse` | 아니요 | 현재 시즌 |
| `CreatureHomeResponse.creature` | `CreatureResponse` | 아니요 | 공동 크리처 Snapshot |
| `CreatureHomeResponse.todayExperience` | int64 | 아니요 | KST 오늘 획득 경험치, 0 이상 |
| `CreatureHomeResponse.recentContributions` | `ContributionActivityResponse[]` | 아니요 | 최신 최대 20건 |
| `CreatureHomeResponse.presentAvatars` | `PresentAvatarResponse[]` | 아니요 | 현재 접속 그룹원, 최대 20명 |
| `CreatureHomeResponse.streamInstanceId` | UUID | 아니요 | 서버 Stream 실행 식별자 |
| `CreatureHomeResponse.sequence` | int64 | 아니요 | Snapshot까지 반영한 마지막 Event 순번, 0 이상 |
| `CreatureResponse.speciesId` | int64 | 예 | 알 기간 미선택이면 `null` |
| `CreatureResponse.speciesName` | string | 예 | 종 미확정이면 `null` |
| `CreatureResponse.stage` | enum | 아니요 | `EGG`, `HATCHLING`, `GROWING`, `MATURE`, `COMPLETE` |
| `CreatureResponse.hatchesAt` | datetime | 예 | 알이면 그룹 시즌 참여 시작 후 72시간, 이미 부화했거나 시즌이 종료됐으면 `null` |
| `CreatureResponse.experience` | int64 | 아니요 | 제한 없는 누적 경험치, 0 이상 |
| `CreatureResponse.currentStageThreshold` | int64 | 아니요 | 현재 단계 시작 경험치 |
| `CreatureResponse.nextStageThreshold` | int64 | 예 | 완전체이면 `null` |
| `ContributionActivityResponse.userId` | int64 | 아니요 | 기여 사용자 ID |
| `ContributionActivityResponse.nickname` | string | 아니요 | 표시 닉네임 Snapshot, 2~20자 |
| `ContributionActivityResponse.experience` | int64 | 아니요 | 해당 완료의 경험치 |
| `ContributionActivityResponse.contribution` | int64 | 아니요 | 해당 완료의 기여도 |
| `ContributionActivityResponse.occurredAt` | datetime | 아니요 | 확정 UTC 시각 |
| `PresentAvatarResponse.userId` | int64 | 아니요 | 그룹 사용자 ID |
| `PresentAvatarResponse.nickname` | string | 아니요 | 표시 닉네임, 2~20자 |
| `PresentAvatarResponse.contribution` | int64 | 아니요 | 현재 시즌·그룹 기여도 |
| `PresentAvatarResponse.rank` | int32 | 아니요 | 현재 그룹 내 순위, 1 이상 |
| `PresentAvatarResponse.me` | boolean | 아니요 | 현재 요청자 여부 |
| `PresentAvatarResponse.equipments` | `EquippedItemResponse[]` | 아니요 | 슬롯별 장착, 최대 4개 |
| `QuestAssignmentResponse.assignmentId` | int64 | 아니요 | 기간별 배정 ID |
| `QuestAssignmentResponse.periodType` | enum | 아니요 | `DAILY`, `WEEKLY` |
| `QuestAssignmentResponse.slotNumber` | int32 | 아니요 | 일일 1~3, 주간 1~7 |
| `QuestAssignmentResponse.title` | string | 아니요 | 배정 Snapshot 제목, 1~100자 |
| `QuestAssignmentResponse.description` | string | 아니요 | 배정 Snapshot 설명, 최대 500자 |
| `QuestAssignmentResponse.difficulty` | enum | 아니요 | `LOW`, `MEDIUM`, `HIGH` |
| `QuestAssignmentResponse.validationType` | enum | 아니요 | `SELF_CHECK`, `SERVER_EVENT`, `AGGREGATE`, `PHOTO_EXIF` |
| `QuestAssignmentResponse.experienceReward` | int64 | 아니요 | 일일 100, 주간 300 |
| `QuestAssignmentResponse.contributionReward` | int64 | 아니요 | 배정 Snapshot 기여도 |
| `QuestAssignmentResponse.startsAt` | datetime | 아니요 | 수행 시작 UTC 시각 |
| `QuestAssignmentResponse.endsAt` | datetime | 아니요 | 수행 종료 UTC 시각 |
| `QuestAssignmentResponse.status` | enum | 아니요 | `AVAILABLE`, `PROCESSING`, `COMPLETED`, `EXPIRED` |
| `QuestAssignmentResponse.completedAt` | datetime | 예 | 미완료면 `null` |
| `QuestCompletionResponse.completionId` | int64 | 아니요 | 완료 ID. 중복 요청도 기존 ID 반환 |
| `QuestCompletionResponse.assignmentId` | int64 | 아니요 | 완료한 배정 ID |
| `QuestCompletionResponse.experienceGranted` | int64 | 아니요 | 실제 지급 경험치 |
| `QuestCompletionResponse.contributionGranted` | int64 | 아니요 | 실제 지급 기여도 |
| `QuestCompletionResponse.creature` | `CreatureResponse` | 아니요 | 반영 후 크리처 상태 |
| `QuestCompletionResponse.completedAt` | datetime | 아니요 | 최초 완료 확정 시각 |
| `CheckInResponse.checkInDate` | date | 아니요 | `Asia/Seoul` 기준일 |
| `CheckInResponse.checkedInAt` | datetime | 아니요 | 서버 기록 UTC 시각 |
| `CheckInResponse.morningCheckIn` | boolean | 아니요 | KST 05:00 이상 09:00 미만 여부 |
| `PhotoSubmissionResponse.submissionId` | int64 | 아니요 | 제출 ID |
| `PhotoSubmissionResponse.status` | enum | 아니요 | 외부 응답은 `VALID`, `INVALID`만 사용 |
| `PhotoSubmissionResponse.failureReason` | enum | 예 | 성공이면 `null`; 실패 시 `EXIF_GPS_MISSING`, `EXIF_GPS_OUT_OF_RANGE`, `EXIF_CAPTURED_AT_MISSING`, `PHOTO_CAPTURED_OUTSIDE_ASSIGNMENT_PERIOD` |
| `PhotoSubmissionResponse.submittedAt` | datetime | 아니요 | 업로드 접수 시각 |
| `MemberRankResponse.rank` | int32 | 아니요 | 1 이상 |
| `MemberRankResponse.userId` | int64 | 아니요 | 사용자 ID |
| `MemberRankResponse.nickname` | string | 아니요 | 현재 시즌은 현재값, 종료 시즌은 Snapshot, 2~20자 |
| `MemberRankResponse.contribution` | int64 | 아니요 | 시즌 기여도, 0 이상 |
| `MemberRankResponse.participantStatus` | enum | 아니요 | `ACTIVE`, `LEFT`, `REMOVED` |
| `GroupRankResponse.rank` | int32 | 아니요 | 1 이상 |
| `GroupRankResponse.groupId` | int64 | 아니요 | 그룹 ID |
| `GroupRankResponse.groupName` | string | 아니요 | 현재값 또는 종료 Snapshot, 2~30자 |
| `GroupRankResponse.score` | int64 | 아니요 | 시즌 그룹 점수, 0 이상 |

Rank 목록의 `meta.currentSubject`는 `rank`, `score`, `subjectId`를 포함하며 대상이 없으면 `null`입니다. Page `meta`에는 `page`, `size`, `totalElements`, `totalPages`를 포함합니다.

### 11.4. 상점·아바타·결제·환불 응답

| DTO.field | 타입 | null 가능 | 의미·허용값 |
|---|---|---|---|
| `ShopItemResponse.itemId` | int64 | 아니요 | 상품 ID |
| `ShopItemResponse.name` | string | 아니요 | 1~100자 상품명 |
| `ShopItemResponse.description` | string | 아니요 | 최대 500자 |
| `ShopItemResponse.slot` | enum | 아니요 | `HEAD`, `FACE`, `BODY`, `ACCESSORY` |
| `ShopItemResponse.priceKrw` | int64 | 아니요 | 원 단위, 0 이상 |
| `ShopItemResponse.imageUrl` | string | 아니요 | 서비스가 허용한 HTTPS 또는 정적 Asset 경로 |
| `ShopItemResponse.status` | enum | 아니요 | `ACTIVE`, `INACTIVE` |
| `ShopItemResponse.owned` | boolean | 아니요 | 현재 사용자의 정상 소유 여부 |
| `OwnedItemResponse.ownedItemId` | int64 | 아니요 | 보유 아이템 ID |
| `OwnedItemResponse.item` | `ShopItemResponse` | 아니요 | 구매 당시 이름·슬롯을 반영한 표시 정보 |
| `OwnedItemResponse.status` | enum | 아니요 | `OWNED_UNUSED`, `OWNED_USED`, `REVOKED` |
| `OwnedItemResponse.usedAt` | datetime | 예 | 최초 장착 전이면 `null`; 설정 후 변경하지 않음 |
| `OwnedItemResponse.equipped` | boolean | 아니요 | 현재 슬롯 장착 여부 |
| `EquippedItemResponse.slot` | enum | 아니요 | 아바타 슬롯 |
| `EquippedItemResponse.ownedItemId` | int64 | 아니요 | 장착한 보유 아이템 ID |
| `EquippedItemResponse.itemId` | int64 | 아니요 | 상품 ID |
| `EquippedItemResponse.itemName` | string | 아니요 | 표시 상품명 |
| `EquippedItemResponse.imageUrl` | string | 아니요 | 장착 외형 Asset URL |
| `EquippedItemResponse.shopDeepLink` | string | 예 | 현재 판매 중일 때만 `/shop?itemId=...` 내부 경로 |
| `AvatarResponse.userId` | int64 | 아니요 | 아바타 소유 사용자 ID |
| `AvatarResponse.nickname` | string | 아니요 | 표시 닉네임 |
| `AvatarResponse.equipments` | `EquippedItemResponse[]` | 아니요 | 슬롯별 최대 4개 |
| `PurchaseCheckoutResponse.purchaseId` | int64 | 아니요 | 내부 구매 ID |
| `PurchaseCheckoutResponse.paymentId` | string | 아니요 | PortOne 요청에 사용하는 서버 주문 번호, 1~100자 |
| `PurchaseCheckoutResponse.orderName` | string | 아니요 | 결제창 표시 주문명, 1~100자 |
| `PurchaseCheckoutResponse.amountKrw` | int64 | 아니요 | 서버 가격 Snapshot |
| `PurchaseCheckoutResponse.currency` | string | 아니요 | 항상 `KRW` |
| `PurchaseCheckoutResponse.storeId` | string | 아니요 | PortOne 공개 Store ID |
| `PurchaseCheckoutResponse.channelKey` | string | 아니요 | 카카오페이 테스트 채널 공개 Key |
| `PurchaseCheckoutResponse.redirectUrl` | string | 아니요 | 허용된 Same-origin 결제 복귀 URL |
| `PurchaseSummaryResponse.purchaseId` | int64 | 아니요 | 구매 ID |
| `PurchaseSummaryResponse.orderNumber` | string | 아니요 | 서버 주문 번호, 1~100자 |
| `PurchaseSummaryResponse.itemName` | string | 아니요 | 구매 Snapshot 상품명, 1~100자 |
| `PurchaseSummaryResponse.amountKrw` | int64 | 아니요 | 구매 Snapshot 금액 |
| `PurchaseSummaryResponse.status` | enum | 아니요 | `READY`, `PAID`, `FAILED`, `CANCELLED` |
| `PurchaseSummaryResponse.createdAt` | datetime | 아니요 | 주문 생성 시각 |
| `PurchaseTimelineResponse.purchase` | `PurchaseSummaryResponse` | 아니요 | 주문 현재 상태 |
| `PurchaseTimelineResponse.paymentTransactions` | `PaymentTransactionResponse[]` | 아니요 | 승인·취소 거래 시각순 |
| `PurchaseTimelineResponse.ownedItem` | `OwnedItemResponse` | 예 | 미지급 또는 회수 전 기록이 없으면 `null` |
| `PurchaseTimelineResponse.refunds` | `RefundResponse[]` | 아니요 | 요청 시각순 환불 이력 |
| `PaymentTransactionResponse.transactionId` | int64 | 아니요 | 내부 거래 ID |
| `PaymentTransactionResponse.type` | enum | 아니요 | `APPROVAL`, `CANCELLATION` |
| `PaymentTransactionResponse.status` | enum | 아니요 | `REQUESTED`, `SUCCESS`, `DEFINITIVE_FAILURE`, `UNKNOWN` |
| `PaymentTransactionResponse.amountKrw` | int64 | 아니요 | 거래 금액 |
| `PaymentTransactionResponse.processedAt` | datetime | 예 | 공급자 처리 미확정이면 `null` |
| `RefundResponse.refundRequestId` | int64 | 아니요 | 환불 요청 ID |
| `RefundResponse.purchaseId` | int64 | 아니요 | 대상 구매 ID |
| `RefundResponse.requestReason` | string | 아니요 | 사용자 입력 사유, 1~500자 |
| `RefundResponse.decisionReason` | string | 예 | 관리자 판단 전이면 `null`, 값이 있으면 1~500자 |
| `RefundResponse.status` | enum | 아니요 | `REQUESTED`, `REJECTED`, `CANCEL_PENDING`, `CANCEL_UNKNOWN`, `COMPLETED`, `FAILED` |
| `RefundResponse.requestedAt` | datetime | 아니요 | 요청 시각 |
| `RefundResponse.reviewedAt` | datetime | 예 | 판단 전이면 `null` |
| `RefundResponse.completedAt` | datetime | 예 | 완료 전이면 `null` |
| `RefundDetailResponse.refund` | `RefundResponse` | 아니요 | 환불 현재 상태 |
| `RefundDetailResponse.purchase` | `PurchaseSummaryResponse` | 아니요 | 주문 Snapshot |
| `RefundDetailResponse.ownedItem` | `OwnedItemResponse` | 예 | 아이템 상태 |
| `RefundDetailResponse.paymentTransactions` | `PaymentTransactionResponse[]` | 아니요 | PG 거래 이력 |
| `RefundDetailResponse.availableActions` | string[] | 아니요 | `APPROVE`, `REJECT`, `RECONCILE` 중 현재 가능한 동작 |

### 11.5. 신고·관리자 응답

| DTO.field | 타입 | null 가능 | 의미·허용값 |
|---|---|---|---|
| `ReportResponse.reportId` | int64 | 아니요 | 신고 ID |
| `ReportResponse.reporter` | `UserDisplayResponse` | 아니요 | 신고자 ID·닉네임 |
| `ReportResponse.target` | `UserDisplayResponse` | 아니요 | 피신고자 ID·닉네임 |
| `ReportResponse.groupId` | int64 | 아니요 | 신고 당시 그룹 ID |
| `ReportResponse.category` | enum | 아니요 | 요청의 신고 분류 |
| `ReportResponse.content` | string | 아니요 | 10~1000자 신고 내용 |
| `ReportResponse.status` | enum | 아니요 | `RECEIVED`, `RESOLVED`, `REJECTED` |
| `ReportResponse.createdAt` | datetime | 아니요 | 접수 시각 |
| `ReportDetailResponse.report` | `ReportResponse` | 아니요 | 신고 본문 |
| `ReportDetailResponse.resolution` | string | 예 | 미처리면 `null` |
| `ReportDetailResponse.handledBy` | `UserDisplayResponse` | 예 | 미처리면 `null` |
| `ReportDetailResponse.handledAt` | datetime | 예 | 미처리면 `null` |
| `ReportDetailResponse.moderationAction` | `ModerationActionResponse` | 예 | 강퇴하지 않았으면 `null` |
| `ModerationActionResponse.actionType` | enum | 아니요 | `REMOVE_INACTIVE`, `REMOVE_REPORTED` |
| `ModerationActionResponse.reason` | string | 아니요 | 최종 실행 근거, 1~1000자 |
| `ModerationActionResponse.executedAt` | datetime | 아니요 | 실행 시각 |
| `UserDisplayResponse.userId` | int64 | 아니요 | 사용자 ID |
| `UserDisplayResponse.nickname` | string | 아니요 | 표시 닉네임, 2~20자 |
| `AdminDashboardResponse.range` | enum | 아니요 | 적용된 기간 |
| `AdminDashboardResponse.groupId` | int64 | 예 | 전체 조회면 `null` |
| `AdminDashboardResponse.aggregatedAt` | datetime | 아니요 | 집계 기준 시각 |
| `AdminDashboardResponse.summary` | `DashboardSummaryResponse` | 아니요 | 요약 카드 값 |
| `AdminDashboardResponse.activityTrend` | `ActivityTrendPointResponse[]` | 아니요 | 날짜별 활동 사용자·완료 건수 |
| `AdminDashboardResponse.stageDistribution` | `CategoryValueResponse[]` | 아니요 | 성장 단계별 그룹 수 |
| `AdminDashboardResponse.topGroupScores` | `CategoryValueResponse[]` | 아니요 | 점수 상위 최대 10개 그룹 |
| `AdminDashboardResponse.paymentRefundTrend` | `PaymentRefundTrendPointResponse[]` | 아니요 | 날짜별 결제·환불 금액 |
| `AdminDashboardResponse.capacityDistribution` | `CategoryValueResponse[]` | 아니요 | 정원 사용률 구간별 그룹 수 |
| `AdminDashboardResponse.recentReports` | `ReportResponse[]` | 아니요 | 최근 미처리 최대 5건 |
| `AdminDashboardResponse.recentRefunds` | `RefundResponse[]` | 아니요 | 최근 처리 대기 최대 5건 |
| `AdminDashboardResponse.recentWarnings` | `OperationalWarningResponse[]` | 아니요 | 최근 미해결 최대 5건 |
| `DashboardSummaryResponse.todayActiveUsers` | int64 | 아니요 | 0 이상 |
| `DashboardSummaryResponse.activeGroups` | int64 | 아니요 | 0 이상 |
| `DashboardSummaryResponse.averageCapacityRate` | decimal | 아니요 | 0~100, 소수점 둘째 자리까지 |
| `DashboardSummaryResponse.questCompletionRate` | decimal | 아니요 | 0~100, 소수점 둘째 자리까지 |
| `DashboardSummaryResponse.testPaymentAmountKrw` | int64 | 아니요 | 0 이상 |
| `DashboardSummaryResponse.pendingRefunds` | int64 | 아니요 | 0 이상 |
| `DashboardSummaryResponse.unresolvedReports` | int64 | 아니요 | 0 이상 |
| `DashboardSummaryResponse.operationalWarnings` | int64 | 아니요 | 0 이상 |
| `ActivityTrendPointResponse.businessDate` | date | 아니요 | KST 기준일 |
| `ActivityTrendPointResponse.activeUsers` | int64 | 아니요 | 해당 날짜 인증 활동 사용자 수, 0 이상 |
| `ActivityTrendPointResponse.questCompletions` | int64 | 아니요 | 해당 날짜 퀘스트 완료 수, 0 이상 |
| `PaymentRefundTrendPointResponse.businessDate` | date | 아니요 | KST 기준일 |
| `PaymentRefundTrendPointResponse.paidAmountKrw` | int64 | 아니요 | 해당 날짜 테스트 결제 완료 금액, 0 이상 |
| `PaymentRefundTrendPointResponse.refundedAmountKrw` | int64 | 아니요 | 해당 날짜 환불 완료 금액, 0 이상 |
| `CategoryValueResponse.category` | string | 아니요 | 단계 Enum, 2~30자 그룹명 또는 `0_25`, `26_50`, `51_75`, `76_99`, `100` |
| `CategoryValueResponse.value` | number | 아니요 | 0 이상의 집계값 |
| `OperationalWarningResponse.warningId` | string | 아니요 | `{type}:{sourceId}` 형태, 최대 100자 |
| `OperationalWarningResponse.type` | enum | 아니요 | `SCHEDULER_FAILED`, `QUEST_TEMPLATE_SHORTAGE`, `CAPACITY_MISMATCH`, `PAYMENT_UNKNOWN`, `REFUND_UNKNOWN`, `PHOTO_DELETE_FAILED` |
| `OperationalWarningResponse.summary` | string | 아니요 | 개인정보 없는 운영 요약, 최대 500자 |
| `OperationalWarningResponse.detectedAt` | datetime | 아니요 | 최초 감지 시각 |
| `OperationalWarningResponse.sourceId` | int64 | 예 | 연결할 내부 대상이 없으면 `null` |

`stageDistribution.category`는 크리처 단계 Enum, `topGroupScores.category`는 그룹명, `capacityDistribution.category`는 `0_25`, `26_50`, `51_75`, `76_99`, `100`을 사용합니다. Google Charts는 이 서버 집계 배열을 표시 형식으로만 변환하고 브라우저에서 원본 행을 다시 집계하지 않습니다.

운영 경고 응답은 현재 미해결 원본 상태만 투영하므로 `status` 필드와 해결된 경고 조회 Filter를 제공하지 않습니다.

## 12. 공통 형식·Enum·입력 검증 결과

### 12.1. JSON 형식

| 형식 | JSON 표현 | 제한 |
|---|---|---|
| `int32`, `int64` | 정수 | 음수가 허용된다고 명시하지 않은 값은 0 이상. ID는 1 이상 |
| `decimal` | 숫자 | 통계 비율에만 사용하고 금액에는 사용하지 않음 |
| `date` | 문자열 | `YYYY-MM-DD`, KST 업무 기준일 |
| `datetime` | 문자열 | ISO 8601 UTC, 예: `2026-11-02T08:00:00Z` |
| `UUID` | 문자열 | 소문자·대문자 모두 수신하되 응답은 표준 하이픈 형식 |
| 금액 | 정수 | 원 단위 `int64`, 통화는 별도 `KRW` |

### 12.2. 공통 상태 Enum

| 영역 | 허용값 |
|---|---|
| 그룹 역할 | `PRIMARY`, `DEPUTY`, `MEMBER` |
| 멤버십 종료 | `ACTIVE`, `LEFT`, `REMOVED_INACTIVE`, `REMOVED_REPORT` |
| 아바타 슬롯 | `HEAD`, `FACE`, `BODY`, `ACCESSORY` |
| 퀘스트 기간 | `DAILY`, `WEEKLY` |
| 퀘스트 난이도 | `LOW`, `MEDIUM`, `HIGH` |
| 크리처 단계 | `EGG`, `HATCHLING`, `GROWING`, `MATURE`, `COMPLETE` |
| 구매 상태 | `READY`, `PAID`, `FAILED`, `CANCELLED` |
| 보유 아이템 상태 | `OWNED_UNUSED`, `OWNED_USED`, `REVOKED` |
| 환불 상태 | `REQUESTED`, `REJECTED`, `CANCEL_PENDING`, `CANCEL_UNKNOWN`, `COMPLETED`, `FAILED` |

### 12.3. 필드 오류

입력 형식 오류는 다음과 같이 `fieldErrors`에 정규화한 필드 경로를 반환합니다. 여러 필드 오류가 있으면 가능한 범위에서 한 번에 반환하되, 인증·CSRF 실패처럼 요청 본문을 평가하면 안 되는 경우에는 공통 오류만 반환합니다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "입력값을 확인해 주세요.",
  "fieldErrors": [
    {
      "field": "nickname",
      "reason": "LENGTH_OUT_OF_RANGE",
      "rejectedValue": null
    }
  ],
  "traceId": "req-01",
  "timestamp": "2026-11-02T08:00:00Z"
}
```

`rejectedValue`는 비밀번호·Token·파일·긴 본문·개인정보를 반환하지 않으며, 일반 필드도 최대 100자로 잘라서 반환합니다. 주요 `reason`은 `REQUIRED`, `INVALID_FORMAT`, `INVALID_ENUM`, `LENGTH_OUT_OF_RANGE`, `OUT_OF_RANGE`, `FILE_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`입니다.

## 13. 초보 구현 체크리스트와 예시

### 13.1. 백엔드 구현 순서

1. 9절의 Operation ID와 같은 이름으로 Controller 메서드를 만듭니다.
2. Path·Query·Header·Body를 10절의 DTO로 변환합니다. Controller에서 Entity를 직접 만들지 않습니다.
3. 형식 검증이 끝나면 Service가 로그인 사용자, 최신 DB 상태와 업무 조건을 검사합니다.
4. Service는 Entity가 아닌 11절 Response DTO를 반환합니다.
5. Controller는 9.5절의 HTTP 상태와 2절 Envelope를 적용합니다.
6. Controller 테스트는 정상 응답, 필드 제한 위반, 권한 부족과 9.5절의 Operation별 업무 오류를 최소 한 번씩 확인합니다.

Java DTO는 팀이 읽기 쉬운 `class` 또는 `record`를 선택할 수 있지만 JSON 필드명과 타입은 문서 표와 같아야 합니다. Query DTO의 고정 정렬은 Repository 또는 Query Service에서 적용하고 클라이언트가 임의 정렬식을 전달하지 못하게 합니다.

### 13.2. JSON 명령 프론트엔드 예시

```javascript
/** 그룹 생성 API를 호출하고 성공 data만 반환합니다. */
export async function createGroup(input, csrfToken) {
  const response = await fetch('/api/v1/groups', {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrfToken,
    },
    body: JSON.stringify({
      name: input.name,
      description: input.description || '',
    }),
  });

  const body = await response.json();
  if (!response.ok) {
    // 화면은 body.message를 표시하고 분기는 body.code로 처리합니다.
    throw body;
  }
  return body.data;
}
```

### 13.3. Multipart 사진 제출 예시

```javascript
/** JPEG 사진을 제출합니다. 브라우저가 boundary를 만들도록 Content-Type을 직접 넣지 않습니다. */
export async function submitQuestPhoto(assignmentId, file, csrfToken) {
  const form = new FormData();
  form.append('assignmentId', String(assignmentId));
  form.append('file', file);

  const response = await fetch('/api/v1/photo-submissions', {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'X-CSRF-TOKEN': csrfToken,
    },
    body: form,
  });
  const body = await response.json();
  if (!response.ok) throw body;
  return body.data; // status는 VALID 또는 INVALID
}
```

### 13.4. 프론트엔드 오류 표시 규칙

- `fieldErrors`가 있으면 `field`와 같은 입력 요소 아래에 오류를 표시합니다.
- `401`이면 현재 화면 동작을 중단하고 `/login`으로 이동합니다.
- `403 CSRF_INVALID`이면 CSRF Token을 한 번 새로 받은 뒤 사용자가 같은 동작을 다시 실행하게 합니다. 자동 무한 재시도는 하지 않습니다.
- `409`는 `code`별 안내를 표시하고 최신 화면 Snapshot을 다시 조회합니다.
- `503`은 외부 시스템 장애 안내와 수동 재시도 버튼을 표시합니다.
- 알 수 없는 `code`도 화면이 깨지지 않게 공통 오류 안내와 `traceId`를 표시합니다.
