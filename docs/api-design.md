# REST API 설계서

**표 읽는 법:** `필수 Y`=항상 있어야 함, `N`=생략 가능, `C`=조건 충족 시 필수 또는 부모 객체가 존재할 때 필수. `null Y`=키는 존재하되 값이 JSON `null`일 수 있음. 배열이 비면 `[]`, 객체가 비면 `{}`를 반환합니다. 요청에서 미지의 키·중복 Query·타입 불일치는 `400 INVALID_FIELD_VALUE`(형식 자체 오류는 `400 INVALID_REQUEST`).

## 1. 공통 HTTP 계약

### 1.1. 경로·Content-Type·시간·권한

| 항목 | 계약 |
|---|---|
| REST Prefix | `/api/v1`; OAuth Callback만 `/oauth2/callback/{provider}` |
| JSON 요청 | `Content-Type: application/json`; Body 없는 명령은 빈 Body. GET/DELETE에서 문서에 없는 Body 금지 |
| 사진 제출 | `multipart/form-data`의 `assignmentId` + `file` |
| 서버 세션 | Spring Session JDBC 쿠키 인증, 기본 비활동 만료 7일, 동시 세션 3개 |
| CSRF | 모든 상태 변경 REST에서 `X-CSRF-TOKEN` 필수; PortOne Webhook은 서명으로 예외 |
| 권한 | `PUBLIC`, `SIGNUP_PENDING`, `AUTHENTICATED`, `GROUP_MEMBER`, `GROUP_ADMIN`(PRIMARY/DEPUTY), `PRIMARY`, `SUPER_ADMIN`, `PORTONE_SIGNATURE` |
| ID | JSON 숫자 int64 양수(1 이상); 정수가 아닌 숫자와 문자열 ID 금지 |
| 시간 | UTC ISO 8601 문자열(예: `2026-11-03T06:00:00Z`); 일/주/시즌·체크인 기준은 Asia/Seoul |
| 금액 | 원(KRW) 단위 int64, 일반 상품 1원 이상; 클라이언트가 결제 금액 입력 금지 |
| 문자열 정규화 | 닉네임/그룹명/검색어는 Unicode NFKC + trim, 중복 판정 시 소문자 정규화; 응답은 표시 문자열 |
| Unknown fields | 내부 API는 선언되지 않은 JSON/Query 필드 거부; 공급자 Webhook만 확장 허용 |
| 페이징 | `page` 0-based 기본 0, `size` 기본 20, 허용 1~50, 정렬 파라미터 받지 않음 |
| 멱등성 | 구매·환불 요청·승인·결과복구: `Idempotency-Key` UUID 필수. 같은 키·내용이면 최초 상태/응답 그대로 반환 |
| 외부 노출 | Entity, DB FK 내부 원장, Secret, OAuth token, PortOne 서버 API Key는 응답하지 않음 |

### 1.2. 성공 Envelope

```json
{"data":{"groupId":1}}
```

**목록 (페이징 API):**

```json
{"data":[],"meta":{"page":0,"size":20,"totalElements":0,"totalPages":0}}
```

랭킹 페이징 API는 `meta.currentSubject`도 반드시 포함합니다. 본문 없는 작업은 `{"data":{}}`로 반환합니다. `getCsrfToken`의 Token은 `data.token`으로 보냅니다. `handleOAuthCallback`만 성공 시 `302` + `Location`이고 JSON Envelope가 없습니다.

### 1.3. 오류 Envelope

```json
{
  "code": "INVALID_FIELD_VALUE",
  "message": "입력값을 확인해 주세요.",
  "fieldErrors": [{"field":"name","message":"2~30자여야 합니다."}],
  "traceId": "request-id",
  "timestamp": "2026-11-03T06:00:00Z"
}
```

| 경로 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| code | string | Y | N | 아래 오류 코드 중 하나, 화면 분기 기준 |
| message | string | Y | N | 사용자 표시용 오류 설명 |
| fieldErrors | array<object> | Y | N | 필드 오류 없으면 [] |
| fieldErrors[].field | string | C | N | JSON 필드 경로 또는 Query/Path 명 |
| fieldErrors[].message | string | C | N | 개별 검증 메시지 |
| traceId | string | Y | N | 추적 ID, 로그와 연결 |
| timestamp | string(date-time) | Y | N | UTC ISO 8601 |

### 1.4. HTTP 상태 및 주요 Enum

| HTTP | 사용 기준 |
|---|---|
| 200 | 일반 조회·수정·삭제 성공 |
| 201 | OAuth 시도·회원가입·그룹 생성·그룹 가입·구매 생성·환불 요청·신고 생성 |
| 202 | PortOne Webhook 정상 수신/무시/중복 수신 |
| 302 | OAuth Callback CSR 이동 |
| 400 | 요청 포맷·필드 검증 실패 |
| 401 | 인증 필요/세션 만료 |
| 403 | 권한/CSRF 위반 |
| 404 | 대상 없음 또는 비공개 |
| 409 | 상태 충돌/마감/정원/멱등 충돌 |
| 413 | 파일 10MiB 초과 |
| 429 | 호출량 초과 |
| 5xx | 서버/외부 서비스 장애(성공으로 간주 금지) |

| 구분 | 허용값 |
|---|---|
| 소셜 공급자(Body) | `GOOGLE`, `NAVER` |
| 콜백 URL 공급자 | `google`, `naver` |
| 로그인 상태 | `ANONYMOUS`, `SIGNUP_PENDING`, `AUTHENTICATED` |
| 온보딩 | `NOT_STARTED`, `SIGNUP_REQUIRED`, `GROUP_REQUIRED`, `COMPLETED` |
| 전체 역할 | `USER`, `SUPER_ADMIN` |
| 그룹 역할 | `PRIMARY`, `DEPUTY`, `MEMBER` |
| 멤버십 상태 | `ACTIVE`, `LEFT`, `REMOVED` |
| 멤버십 종료 사유 | `SELF`, `INACTIVE`, `REPORT` |
| 시즌 | `SCHEDULED`, `ACTIVE`, `CLOSING`, `ENDED` |
| 시즌 그룹 | `PREPARING`, `ACTIVE`, `ENDED` |
| 크리처 단계 | `EGG`, `HATCHLING`, `GROWING`, `MATURE`, `COMPLETE` |
| 퀘스트 기간 | `DAILY`, `WEEKLY` |
| 퀘스트 검증 | `SELF_CHECK`, `SERVER_EVENT`, `AGGREGATE`, `PHOTO_EXIF` |
| 퀘스트 난이도 | `LOW`, `MEDIUM`, `HIGH` |
| 아바타 슬롯 | HEAD \| FACE \| BODY \| ACCESSORY |
| 보유 | `OWNED_UNUSED`, `OWNED_USED`, `REVOKED` |
| 구매 | READY \| PAID \| FAILED \| CANCELLED |
| 결제 거래 | `REQUESTED`, `SUCCESS`, `DEFINITIVE_FAILURE`, `UNKNOWN` |
| 환불 | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |
| 신고 | `RECEIVED`, `RESOLVED`, `REJECTED` |
| 신고 분류 [보완안] | `HARASSMENT`, `SPAM`, `INAPPROPRIATE_CONTENT`, `OTHER` |
| 사진 판정 | `PENDING`, `VALID`, `INVALID` |

### 1.5. 오류 코드 사전

| HTTP | code | 상태·원인 |
|---|---|---|
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `SLOT_MISMATCH` | 보유 아이템과 장착 슬롯 불일치 |
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `SIGNUP_CONTEXT_EXPIRED` | 가입 대기 30분 만료 또는 소실 |
| 409 | `NICKNAME_ALREADY_USED` | 정규화된 닉네임 중복 |
| 409 | `GROUP_NAME_ALREADY_USED` | 정규화된 그룹명 중복 |
| 409 | `GROUP_FULL` | 그룹 정원 20명 초과 |
| 409 | `ALREADY_IN_GROUP` | 이미 활성 그룹 소속 |
| 409 | `JOIN_COOLDOWN_ACTIVE` | 탈퇴/강퇴 후 24시간 가입 제한 |
| 409 | `INVITE_INVALID` | 초대 링크 폐기·오류·대상 가입 불가 |
| 409 | `INACTIVITY_REQUIREMENT_NOT_MET` | 비활동 강퇴 조건 미충족 |
| 409 | `PRIMARY_TRANSFER_REQUIRED` | 주 관리자 이전이 필요 |
| 409 | `CREATURE_SELECTION_CLOSED` | 종 선택 72시간 종료 |
| 409 | `QUEST_PERIOD_ENDED` | 퀘스트 유효 기간 밖 |
| 409 | `QUEST_COMPLETION_NOT_ALLOWED` | 해당 판정 유형 직접 완료 불가 |
| 409 | `PHOTO_ATTEMPT_LIMIT_EXCEEDED` | 사진 제출 횟수 5회 초과 |
| 409 | `FIRST_USE_CONFIRMATION_REQUIRED` | 최초 장착 동의 필요 |
| 409 | `PAYMENT_AMOUNT_MISMATCH` | PG 실제 금액과 주문 금액 불일치 |
| 409 | `PAYMENT_PENDING` | 결제 상태 미확정 |
| 409 | `PAYMENT_RESULT_UNKNOWN` | 결제 최신 상태 확인 실패 |
| 409 | `REFUND_NOT_ALLOWED_USED_ITEM` | 사용된 아이템 환불 불가 |
| 409 | `REFUND_ALREADY_ACTIVE` | 진행 중 환불이 존재 |
| 409 | `PG_CANCEL_RESULT_UNKNOWN` | PG 취소 결과 불명 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 Payload 전달 |
| 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 같은 멱등 키 처리 중 |
| 413 | `FILE_TOO_LARGE` | 사진 10 MiB 초과 |
| 429 | `RATE_LIMIT_EXCEEDED` | 호출량/동시 처리 제한 초과 |

**오류 조건 우선순위:** 인증/서명 → CSRF → 권한·리소스 범위 → 형식 및 필드 검증 → 업무 상태·동시성. 필드 오류와 공통 상태 오류가 함께 발생하면 선행 단계 오류로 응답합니다. 동일 주문·환불의 멱등 재요청은 최초 HTTP 상태와 Body를 그대로 반환합니다.

## 2. 인증·초대 API

### getCsrfToken — `GET /api/v1/auth/csrf`

- **Operation ID:** `getCsrfToken` · **요구사항:** `REQ-AUTH-004` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 익명 세션에도 CSRF 토큰 발급 허용; Set-Cookie는 필요 시 서버 세션 쿠키.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | N | N | 있으면 현재 세션 전달, 없으면 익명 세션으로 처리 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.headerName | string | Y | N | 고정 X-CSRF-TOKEN | 헤더명 |
| data.token | string | Y | N | 1자 이상 | 현재 세션 CSRF 토큰 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### createOAuthAttempt — `POST /api/v1/auth/oauth-attempts`

- **Operation ID:** `createOAuthAttempt` · **요구사항:** `REQ-AUTH-001, REQ-INV-001` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `201`
- **엔드포인트 조건:** 세션당 10분 5회, IP당 10분 30회. 초대 문맥은 서버 측에 보존하고 클라이언트는 토큰을 Body로 전달하지 않는다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | N | N | 있으면 현재 세션 전달, 없으면 익명 세션으로 처리 |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | provider | string(enum) | Y | N | GOOGLE \| NAVER |
| Body | returnRoute | string(enum) | N | N | DEFAULT \| INVITE; 서버에 등록된 CSR 경로만 허용; 로그인 완료 후 이동 목적 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.authorizationUrl | string(uri) | Y | N | https://, 공급자 인가 URL | 브라우저 이동 주소 |
| data.expiresAt | string(date-time) | Y | N | UTC ISO 8601 | 인가 시도 만료 시각 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 429 | `RATE_LIMIT_EXCEEDED` | 호출량/동시 처리 제한 초과 |

### handleOAuthCallback — `GET /oauth2/callback/{provider}`

- **Operation ID:** `handleOAuthCallback` · **요구사항:** `REQ-AUTH-001` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `302`
- **엔드포인트 조건:** JSON data가 아닌 302 Location 내부 CSR 경로로 리다이렉트한다. 실패도 내부 경로와 서버 측 오류 문맥으로 처리, 외부 returnUrl 금지.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Path | provider | string(enum) | Y | N | google \| naver |
| Query | code | string | C | N | 1~2048자, error가 없을 때 필수 |
| Query | state | string | C | N | 32~128자, 원본 요청과 일치, error가 없을 때 필수 |
| Query | error | string | C | N | 공급자 오류 시 코드, 있으면 code와 동시 사용 불가 |

**성공 응답 필드**

| 위치 | 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|---|
| Header | Location | string(uri) | Y | N | 검증된 동일 Origin CSR Route |
| Body | — | — | — | — | JSON Envelope 없음 |

**오류 응답**

인가 오류·`state`/세션 검증 실패 시 **토큰과 코드가 URL에 노출되지 않는 내부 오류 경로로 302 Redirect**. 아래 오류 코드는 내부 사유 분류이며, 일반 JSON 응답으로 리다이렉트하지 않습니다.

| HTTP | code | 조건 |
|---|---|---|
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |

### getSignupContext — `GET /api/v1/auth/signup-context`

- **Operation ID:** `getSignupContext` · **요구사항:** `REQ-AUTH-002, REQ-AUTH-003` · **인증·권한:** `SIGNUP_PENDING` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.provider | string(enum) | Y | N | GOOGLE \| NAVER |  |
| data.email | string | Y | N | 1~320자 | 공급자 이메일 |
| data.suggestedNickname | string | Y | Y | 0~20자 | 추천 닉네임 |
| data.invitation | object | Y | Y | 초대 문맥이 없으면 null |  |
| data.invitation.groupId | integer(int64) | C | N | 1 이상 | 초대 대상 그룹 ID |
| data.invitation.groupName | string | C | N | 2~30자 | 초대 대상 그룹명 |
| data.expiresAt | string(date-time) | Y | N | UTC ISO 8601 | 가입 대기 종료 시각 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `SIGNUP_CONTEXT_EXPIRED` | 가입 대기 30분 만료 또는 소실 |

### completeSignup — `POST /api/v1/auth/signup`

- **Operation ID:** `completeSignup` · **요구사항:** `REQ-AUTH-002, REQ-INV-001` · **인증·권한:** `SIGNUP_PENDING` · **성공 HTTP:** `201`
- **엔드포인트 조건:** 초대 가입이 실패해도 계정 생성은 유지하고 `201`을 반환한다. 그 경우 data.onboardingState=GROUP_REQUIRED이며 실패 원인은 data.onboardingReason으로 제공한다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | nickname | string | Y | N | NFKC·trim 후 2~20자, 정규화 비교 시 중복 불가 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.authenticationState | string(enum) | Y | N | ANONYMOUS \| SIGNUP_PENDING \| AUTHENTICATED |  |
| data.onboardingState | string(enum) | Y | N | NOT_STARTED \| SIGNUP_REQUIRED \| GROUP_REQUIRED \| COMPLETED |  |
| data.user | object | Y | Y | 비로그인/가입 대기에서는 null |  |
| data.user.userId | integer(int64) | C | N | 1 이상 |  |
| data.user.nickname | string | C | N | 2~20자 |  |
| data.membership | object | Y | Y | 그룹 미가입이면 null |  |
| data.membership.membershipId | integer(int64) | C | N | 1 이상 |  |
| data.membership.groupId | integer(int64) | C | N | 1 이상 |  |
| data.membership.role | string(enum) | C | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.membership.status | string(enum) | C | N | ACTIVE \| LEFT \| REMOVED |  |
| data.membership.joinedAt | string(date-time) | C | N | UTC ISO 8601 |  |
| data.roles | array<string(enum)> | Y | N | 요소: SUPER_ADMIN, PRIMARY, DEPUTY, MEMBER; 없으면 [] |  |
| data.restrictionUntil | string(date-time) | Y | Y | UTC ISO 8601 | 그룹 가입 제한 없으면 null |
| data.onboardingReason | string(enum) | Y | Y | INVITE_INVALID \| GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 없으면 null | 초대 가입 실패 원인, 응답 후 세션에서 제거 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `SIGNUP_CONTEXT_EXPIRED` | 가입 대기 30분 만료 또는 소실 |
| 409 | `NICKNAME_ALREADY_USED` | 정규화된 닉네임 중복 |

### getCurrentUser — `GET /api/v1/me`

- **Operation ID:** `getCurrentUser` · **요구사항:** `REQ-AUTH-004` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 익명/가입 대기/그룹 미가입/정상 그룹원/그룹 없는 SUPER_ADMIN까지 동일 응답 구조. 초대 가입 실패 사유는 멤버십 유무와 관계없이 다음 인증 응답에서 한 번 반환한 뒤 세션에서 제거한다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | N | N | 있으면 현재 세션 전달, 없으면 익명 세션으로 처리 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.authenticationState | string(enum) | Y | N | ANONYMOUS \| SIGNUP_PENDING \| AUTHENTICATED |  |
| data.onboardingState | string(enum) | Y | N | NOT_STARTED \| SIGNUP_REQUIRED \| GROUP_REQUIRED \| COMPLETED |  |
| data.user | object | Y | Y | 비로그인/가입 대기에서는 null |  |
| data.user.userId | integer(int64) | C | N | 1 이상 |  |
| data.user.nickname | string | C | N | 2~20자 |  |
| data.membership | object | Y | Y | 그룹 미가입이면 null |  |
| data.membership.membershipId | integer(int64) | C | N | 1 이상 |  |
| data.membership.groupId | integer(int64) | C | N | 1 이상 |  |
| data.membership.role | string(enum) | C | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.membership.status | string(enum) | C | N | ACTIVE \| LEFT \| REMOVED |  |
| data.membership.joinedAt | string(date-time) | C | N | UTC ISO 8601 |  |
| data.roles | array<string(enum)> | Y | N | 요소: SUPER_ADMIN, PRIMARY, DEPUTY, MEMBER; 없으면 [] |  |
| data.restrictionUntil | string(date-time) | Y | Y | UTC ISO 8601 | 그룹 가입 제한 없으면 null |
| data.onboardingReason | string(enum) | Y | Y | INVITE_INVALID \| GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 없으면 null | 초대 가입 실패 원인, 응답 후 세션에서 제거 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### logout — `POST /api/v1/auth/logout`

- **Operation ID:** `logout` · **요구사항:** `REQ-AUTH-004` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 현재 애플리케이션 세션 무효화; 소셜 공급자 로그아웃 없음.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getPublicInvitation — `GET /api/v1/invitations/{token}`

- **Operation ID:** `getPublicInvitation` · **요구사항:** `REQ-INV-001` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | N | N | 있으면 현재 세션 전달, 없으면 익명 세션으로 처리 |
| Path | token | string | Y | N | URL-safe 32~128자; 원문 로그 금지 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.groupName | string | Y | N | 2~30자 |  |
| data.description | string | Y | N | 0~500자 |  |
| data.currentMemberCount | integer | Y | N | 0~20 |  |
| data.capacity | integer | Y | N | 고정 20 |  |
| data.joinable | boolean | Y | N | true/false |  |
| data.joinBlockedReason | string(enum) | Y | Y | GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| INVITE_INVALID \| ALREADY_IN_GROUP; 가입 가능하면 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `INVITE_INVALID` | 초대 링크 폐기·오류·대상 가입 불가 |

### acceptInvitation — `POST /api/v1/invitations/{token}/accept`

- **Operation ID:** `acceptInvitation` · **요구사항:** `REQ-INV-001` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 인증 전에는 초대 문맥 보존과 로그인/가입 안내; 인증 후에는 조건 재검사 및 그룹 가입. 같은 세션에서 다시 수락하면 마지막으로 수락한 초대를 OAuth 콜백과 가입 대기에 동기화한다. 가입 대기가 만료 시각과 같거나 지난 경우 만료된 가입 대기를 제거하고 초대 문맥을 유지한 채 `/login`을 반환한다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | N | N | 있으면 현재 세션 전달, 없으면 익명 세션으로 처리 |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Path | token | string | Y | N | URL-safe 32~128자; 원문 로그 금지 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.nextRoute | string | Y | N | /login \| /signup \| /groups/select \| /creature \| /admin 등 내부 CSR 경로 |  |
| data.reasonCode | string | Y | Y | 없으면 null | 이동·실패 안내용 서버 코드 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `INVITE_INVALID` | 초대 링크 폐기·오류·대상 가입 불가 |
| 409 | `GROUP_FULL` | 그룹 정원 20명 초과 |
| 409 | `ALREADY_IN_GROUP` | 이미 활성 그룹 소속 |
| 409 | `JOIN_COOLDOWN_ACTIVE` | 탈퇴/강퇴 후 24시간 가입 제한 |

### rejectInvitation — `POST /api/v1/invitations/{token}/reject`

- **Operation ID:** `rejectInvitation` · **요구사항:** `REQ-INV-001` · **인증·권한:** `PUBLIC` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 초대 문맥과 가입 대기를 먼저 제거하고 일반 로그인/가입 흐름으로 이동한다. 링크가 없거나 무효이면 세션 정리를 마친 뒤 아래 404/409 오류를 반환한다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | N | N | 있으면 현재 세션 전달, 없으면 익명 세션으로 처리 |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Path | token | string | Y | N | URL-safe 32~128자; 원문 로그 금지 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.nextRoute | string | Y | N | /login \| /signup \| /groups/select \| /creature \| /admin 등 내부 CSR 경로 |  |
| data.reasonCode | string | Y | Y | 없으면 null | 이동·실패 안내용 서버 코드 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `INVITE_INVALID` | 초대 링크 폐기·오류·대상 가입 불가 |

## 3. 그룹 API

### listPublicGroups — `GET /api/v1/groups`

- **Operation ID:** `listPublicGroups` · **요구사항:** `REQ-GRP-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 정렬은 서버 고정. meta.page, size, totalElements, totalPages를 포함.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | q | string | N | N | Unicode NFKC + trim 후 0~30자; 빈 문자열은 검색 없음 |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].groupId | integer(int64) | Y | N | 1 이상 |  |
| data[].name | string | Y | N | 2~30자 |  |
| data[].description | string | Y | N | 0~500자 |  |
| data[].currentMemberCount | integer | Y | N | 0~20 |  |
| data[].capacity | integer | Y | N | 고정 20 |  |
| data[].remainingCapacity | integer | Y | N | 0~20 |  |
| data[].joinable | boolean | Y | N | true/false |  |
| data[].joinBlockedReason | string(enum) | Y | Y | GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 가입 가능하면 null |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### createGroup — `POST /api/v1/groups`

- **Operation ID:** `createGroup` · **요구사항:** `REQ-GRP-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `201`
- **엔드포인트 조건:** 새 그룹과 PRIMARY 멤버십 및 활성 초대 링크를 생성한다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | name | string | Y | N | NFKC·trim 후 2~30자, 대소문자 무시 중복 불가 |
| Body | description | string | N | N | 0~500자, 미전송 시 빈 문자열; 그룹 소개 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.group | object | Y | N | 필수 |  |
| data.group.group | object | Y | N | 필수 |  |
| data.group.group.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.group.group.name | string | Y | N | 2~30자 |  |
| data.group.group.description | string | Y | N | 0~500자 |  |
| data.group.group.currentMemberCount | integer | Y | N | 0~20 |  |
| data.group.group.capacity | integer | Y | N | 고정 20 |  |
| data.group.group.remainingCapacity | integer | Y | N | 0~20 |  |
| data.group.group.joinable | boolean | Y | N | true/false |  |
| data.group.group.joinBlockedReason | string(enum) | Y | Y | GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 가입 가능하면 null |  |
| data.group.primaryAdmin | object | Y | Y | 활성 그룹 필수 | 비활성 그룹에서는 null |
| data.group.primaryAdmin.userId | integer(int64) | C | N | 1 이상 |  |
| data.group.primaryAdmin.nickname | string | C | N | 2~20자 |  |
| data.group.myMembership | object | Y | Y | 내가 그룹 미가입이면 null |  |
| data.group.myMembership.membershipId | integer(int64) | C | N | 1 이상 |  |
| data.group.myMembership.groupId | integer(int64) | C | N | 1 이상 |  |
| data.group.myMembership.role | string(enum) | C | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.group.myMembership.status | string(enum) | C | N | ACTIVE \| LEFT \| REMOVED |  |
| data.group.myMembership.joinedAt | string(date-time) | C | N | UTC ISO 8601 |  |
| data.initialInvitationUrl | string(uri) | Y | N | 동일 Origin /invite/{token} | 신규 그룹 초대 링크 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `GROUP_NAME_ALREADY_USED` | 정규화된 그룹명 중복 |
| 409 | `ALREADY_IN_GROUP` | 이미 활성 그룹 소속 |
| 409 | `JOIN_COOLDOWN_ACTIVE` | 탈퇴/강퇴 후 24시간 가입 제한 |

### proofreadGroupDescription — `POST /api/v1/ai/proofreads`

- **Operation ID:** `proofreadGroupDescription` · **요구사항:** `REQ-AI-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 서버 외부 연동; 교정 제안은 저장하지 않는다. 분당 5회, KST 일 30회, 동시 2건.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | text | string | Y | N | trim 후 1~500자; 한국어 소개 문장 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.originalText | string | Y | N | 1~500자 |  |
| data.suggestedText | string | Y | N | 0~500자 | 교정 제안 |
| data.changed | boolean | Y | N | true/false | 내용 변경 여부 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 429 | `RATE_LIMIT_EXCEEDED` | 호출량/동시 처리 제한 초과 |

### joinGroup — `POST /api/v1/groups/{groupId}/memberships`

- **Operation ID:** `joinGroup` · **요구사항:** `REQ-GRP-001, REQ-GRP-002` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `201`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Path | groupId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.membershipId | integer(int64) | Y | N | 1 이상 |  |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.role | string(enum) | Y | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.status | string(enum) | Y | N | ACTIVE \| LEFT \| REMOVED |  |
| data.joinedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.nickname | string | Y | N | 2~20자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `GROUP_FULL` | 그룹 정원 20명 초과 |
| 409 | `ALREADY_IN_GROUP` | 이미 활성 그룹 소속 |
| 409 | `JOIN_COOLDOWN_ACTIVE` | 탈퇴/강퇴 후 24시간 가입 제한 |

### getMyGroup — `GET /api/v1/groups/me`

- **Operation ID:** `getMyGroup` · **요구사항:** `REQ-GRP-002, REQ-GRP-003` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.group | object | Y | N | 필수 |  |
| data.group.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.group.name | string | Y | N | 2~30자 |  |
| data.group.description | string | Y | N | 0~500자 |  |
| data.group.currentMemberCount | integer | Y | N | 0~20 |  |
| data.group.capacity | integer | Y | N | 고정 20 |  |
| data.group.remainingCapacity | integer | Y | N | 0~20 |  |
| data.group.joinable | boolean | Y | N | true/false |  |
| data.group.joinBlockedReason | string(enum) | Y | Y | GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 가입 가능하면 null |  |
| data.primaryAdmin | object | Y | Y | 활성 그룹 필수 | 비활성 그룹에서는 null |
| data.primaryAdmin.userId | integer(int64) | C | N | 1 이상 |  |
| data.primaryAdmin.nickname | string | C | N | 2~20자 |  |
| data.myMembership | object | Y | Y | 내가 그룹 미가입이면 null |  |
| data.myMembership.membershipId | integer(int64) | C | N | 1 이상 |  |
| data.myMembership.groupId | integer(int64) | C | N | 1 이상 |  |
| data.myMembership.role | string(enum) | C | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.myMembership.status | string(enum) | C | N | ACTIVE \| LEFT \| REMOVED |  |
| data.myMembership.joinedAt | string(date-time) | C | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

### listMyGroupMembers — `GET /api/v1/groups/me/members`

- **Operation ID:** `listMyGroupMembers` · **요구사항:** `REQ-GRP-003, REQ-GRP-004, REQ-GRP-005` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].userId | integer(int64) | Y | N | 1 이상 |  |
| data[].nickname | string | Y | N | 2~20자 |  |
| data[].membershipId | integer(int64) | Y | N | 1 이상 |  |
| data[].role | string(enum) | Y | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data[].status | string(enum) | Y | N | ACTIVE \| LEFT \| REMOVED |  |
| data[].joinedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].lastActiveAt | string(date-time) | Y | Y | UTC ISO 8601 | 접속 기록 없으면 null |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### leaveMyGroup — `DELETE /api/v1/groups/me/membership`

- **Operation ID:** `leaveMyGroup` · **요구사항:** `REQ-GRP-003` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 주 관리자 단독 탈퇴는 그룹 비활성화; 다른 회원이 있으면 이전 후 탈퇴.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.endedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.exitReason | string(enum) | Y | N | SELF \| INACTIVE \| REPORT |  |
| data.restrictionUntil | string(date-time) | Y | N | UTC ISO 8601, endedAt+24시간 |  |
| data.groupDeactivated | boolean | Y | N | true/false |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `PRIMARY_TRANSFER_REQUIRED` | 주 관리자 이전이 필요 |

### changeMemberRole — `PATCH /api/v1/groups/me/members/{userId}/role`

- **Operation ID:** `changeMemberRole` · **요구사항:** `REQ-GRP-004` · **인증·권한:** `PRIMARY` · **성공 HTTP:** `200`
- **엔드포인트 조건:** PRIMARY만 부 관리자 부여·회수 가능.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | userId | integer(int64) | Y | N | 1 이상 |
| Body | role | string(enum) | Y | N | MEMBER \| DEPUTY |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.nickname | string | Y | N | 2~20자 |  |
| data.membershipId | integer(int64) | Y | N | 1 이상 |  |
| data.role | string(enum) | Y | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.status | string(enum) | Y | N | ACTIVE \| LEFT \| REMOVED |  |
| data.joinedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.lastActiveAt | string(date-time) | Y | Y | UTC ISO 8601 | 접속 기록 없으면 null |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### removeGroupMember — `POST /api/v1/groups/me/members/{userId}/removals`

- **Operation ID:** `removeGroupMember` · **요구사항:** `REQ-GRP-005` · **인증·권한:** `GROUP_ADMIN` · **성공 HTTP:** `200`
- **엔드포인트 조건:** DEPUTY는 비활동 MEMBER만, PRIMARY는 DEPUTY 및 비활동 MEMBER를 강퇴 가능.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | userId | integer(int64) | Y | N | 1 이상 |
| Body | reason | string | Y | N | trim 후 1~500자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.endedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.exitReason | string(enum) | Y | N | SELF \| INACTIVE \| REPORT |  |
| data.restrictionUntil | string(date-time) | Y | N | UTC ISO 8601, endedAt+24시간 |  |
| data.groupDeactivated | boolean | Y | N | true/false |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `INACTIVITY_REQUIREMENT_NOT_MET` | 비활동 강퇴 조건 미충족 |

### transferPrimaryAdmin — `POST /api/v1/groups/me/primary-transfer`

- **Operation ID:** `transferPrimaryAdmin` · **요구사항:** `REQ-GRP-004` · **인증·권한:** `PRIMARY` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 대상은 같은 그룹의 활성 회원.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | targetUserId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.group | object | Y | N | 필수 |  |
| data.group.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.group.name | string | Y | N | 2~30자 |  |
| data.group.description | string | Y | N | 0~500자 |  |
| data.group.currentMemberCount | integer | Y | N | 0~20 |  |
| data.group.capacity | integer | Y | N | 고정 20 |  |
| data.group.remainingCapacity | integer | Y | N | 0~20 |  |
| data.group.joinable | boolean | Y | N | true/false |  |
| data.group.joinBlockedReason | string(enum) | Y | Y | GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 가입 가능하면 null |  |
| data.primaryAdmin | object | Y | Y | 활성 그룹 필수 | 비활성 그룹에서는 null |
| data.primaryAdmin.userId | integer(int64) | C | N | 1 이상 |  |
| data.primaryAdmin.nickname | string | C | N | 2~20자 |  |
| data.myMembership | object | Y | Y | 내가 그룹 미가입이면 null |  |
| data.myMembership.membershipId | integer(int64) | C | N | 1 이상 |  |
| data.myMembership.groupId | integer(int64) | C | N | 1 이상 |  |
| data.myMembership.role | string(enum) | C | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.myMembership.status | string(enum) | C | N | ACTIVE \| LEFT \| REMOVED |  |
| data.myMembership.joinedAt | string(date-time) | C | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### getInvitationManagement — `GET /api/v1/groups/me/invitation`

- **Operation ID:** `getInvitationManagement` · **요구사항:** `REQ-INV-002` · **인증·권한:** `PRIMARY` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.invitationUrl | string(uri) | Y | N | 동일 Origin /invite/{token} |  |
| data.issuedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.issuedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.unlimitedUses | boolean | Y | N | 고정 true |  |
| data.expiresAt | string(date-time) | Y | Y | 항상 null(무기한) |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### rotateInvitation — `POST /api/v1/groups/me/invitation/rotate`

- **Operation ID:** `rotateInvitation` · **요구사항:** `REQ-INV-002` · **인증·권한:** `PRIMARY` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.invitationUrl | string(uri) | Y | N | 동일 Origin /invite/{token} |  |
| data.issuedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.issuedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.unlimitedUses | boolean | Y | N | 고정 true |  |
| data.expiresAt | string(date-time) | Y | Y | 항상 null(무기한) |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

## 4. 시즌·크리처 API

### getCurrentSeason — `GET /api/v1/seasons/current`

- **Operation ID:** `getCurrentSeason` · **요구사항:** `REQ-SEA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.seasonId | integer(int64) | Y | N | 1 이상 |  |
| data.seasonNumber | integer | Y | N | 1 이상 |  |
| data.status | string(enum) | Y | N | SCHEDULED \| ACTIVE \| CLOSING \| ENDED |  |
| data.startAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.endAt | string(date-time) | Y | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getCreatureHome — `GET /api/v1/groups/me/creature-home`

- **Operation ID:** `getCreatureHome` · **요구사항:** `REQ-SEA-001, REQ-SEA-002, REQ-SEA-003, REQ-AVA-002, REQ-RT-001` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 재접속/이벤트 누락 시 Snapshot 재조회. Presence는 단일 인스턴스 메모리 기준.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.groupName | string | Y | N | 2~30자 |  |
| data.season | object | Y | N | 필수 |  |
| data.season.seasonId | integer(int64) | Y | N | 1 이상 |  |
| data.season.seasonNumber | integer | Y | N | 1 이상 |  |
| data.season.status | string(enum) | Y | N | SCHEDULED \| ACTIVE \| CLOSING \| ENDED |  |
| data.season.startAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.season.endAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.creature | object | Y | N | 필수 |  |
| data.creature.groupCreatureId | integer(int64) | Y | N | 1 이상 |  |
| data.creature.creatureSpeciesId | integer(int64) | Y | Y | 알 기간 null |  |
| data.creature.growthStage | string(enum) | Y | N | EGG \| HATCHLING \| GROWING \| MATURE \| COMPLETE |  |
| data.creature.totalExperience | integer(int64) | Y | N | 0 이상 |  |
| data.creature.nextStageAtExperience | integer(int64) | Y | Y | 다음 성장 임계값; 최종 단계 null |  |
| data.creature.selectionDeadlineAt | string(date-time) | Y | N | UTC ISO 8601 | 선택 기한 |
| data.todayExperience | integer(int64) | Y | N | 0 이상 | 오늘 그룹 합산 경험치 |
| data.onlineMembers | array<object> | Y | N | 빈 값 [] |  |
| data.onlineMembers[].userId | integer(int64) | Y | N | 1 이상 |  |
| data.onlineMembers[].nickname | string | Y | N | 2~20자 |  |
| data.onlineMembers[].rank | integer | Y | Y | 1 이상; 집계 불가면 null |  |
| data.onlineMembers[].contributionScore | integer(int64) | Y | N | 0 이상 |  |
| data.onlineMembers[].avatar | object | Y | N | 필수 |  |
| data.onlineMembers[].avatar.equipments | object | Y | N | 네 슬롯 항상 존재 |  |
| data.onlineMembers[].avatar.equipments.HEAD | object | Y | Y | 미장착이면 null |  |
| data.onlineMembers[].avatar.equipments.HEAD.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.HEAD.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.HEAD.name | string | C | N | 1~100자 |  |
| data.onlineMembers[].avatar.equipments.HEAD.assetKey | string | C | N | 1~255자 |  |
| data.onlineMembers[].avatar.equipments.FACE | object | Y | Y | 미장착이면 null |  |
| data.onlineMembers[].avatar.equipments.FACE.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.FACE.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.FACE.name | string | C | N | 1~100자 |  |
| data.onlineMembers[].avatar.equipments.FACE.assetKey | string | C | N | 1~255자 |  |
| data.onlineMembers[].avatar.equipments.BODY | object | Y | Y | 미장착이면 null |  |
| data.onlineMembers[].avatar.equipments.BODY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.BODY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.BODY.name | string | C | N | 1~100자 |  |
| data.onlineMembers[].avatar.equipments.BODY.assetKey | string | C | N | 1~255자 |  |
| data.onlineMembers[].avatar.equipments.ACCESSORY | object | Y | Y | 미장착이면 null |  |
| data.onlineMembers[].avatar.equipments.ACCESSORY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.ACCESSORY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.onlineMembers[].avatar.equipments.ACCESSORY.name | string | C | N | 1~100자 |  |
| data.onlineMembers[].avatar.equipments.ACCESSORY.assetKey | string | C | N | 1~255자 |  |
| data.recentContributions | array<object> | Y | N | 빈 값 [] |  |
| data.recentContributions[].userId | integer(int64) | Y | N | 1 이상 |  |
| data.recentContributions[].nickname | string | Y | N | 2~20자 |  |
| data.recentContributions[].amount | integer(int64) | Y | N | 1 이상 |  |
| data.recentContributions[].occurredAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.snapshotAt | string(date-time) | Y | N | UTC ISO 8601 | Snapshot 생성 시각 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### listActiveCreatureSpecies — `GET /api/v1/creature-species`

- **Operation ID:** `listActiveCreatureSpecies` · **요구사항:** `REQ-SEA-002` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].creatureSpeciesId | integer(int64) | Y | N | 1 이상 |  |
| data[].code | string | Y | N | 1~50자 |  |
| data[].name | string | Y | N | 1~50자 |  |
| data[].assetKey | string | Y | N | 1~255자 |  |
| data[].displayOrder | integer | Y | N | 0 이상 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### selectCreatureSpecies — `PUT /api/v1/groups/me/seasons/current/creature-selection`

- **Operation ID:** `selectCreatureSpecies` · **요구사항:** `REQ-SEA-002` · **인증·권한:** `GROUP_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | creatureSpeciesId | integer(int64) | Y | N | 1 이상; 활성 종만 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.seasonId | integer(int64) | Y | N | 1 이상 |  |
| data.creatureSpeciesId | integer(int64) | Y | N | 1 이상 |  |
| data.selectedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.selectedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.selectionDeadlineAt | string(date-time) | Y | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `CREATURE_SELECTION_CLOSED` | 종 선택 72시간 종료 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

## 5. 퀘스트·체크인 API

### listCurrentQuests — `GET /api/v1/quests/current`

- **Operation ID:** `listCurrentQuests` · **요구사항:** `REQ-QST-001, REQ-QST-002` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 일일 3, 주간 7 배정. 완료 여부는 인증된 사용자 기준.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | periodType | string(enum) | N | N | DAILY \| WEEKLY; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].assignmentId | integer(int64) | Y | N | 1 이상 |  |
| data[].periodId | integer(int64) | Y | N | 1 이상 |  |
| data[].periodType | string(enum) | Y | N | DAILY \| WEEKLY |  |
| data[].periodStartAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].periodEndAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].slotNumber | integer | Y | N | 1 이상 |  |
| data[].templateCode | string | Y | N | 1~30자 |  |
| data[].title | string | Y | N | 1~100자 |  |
| data[].description | string | Y | N | 0~500자 |  |
| data[].difficulty | string(enum) | Y | N | LOW \| MEDIUM \| HIGH |  |
| data[].verificationType | string(enum) | Y | N | SELF_CHECK \| SERVER_EVENT \| AGGREGATE \| PHOTO_EXIF |  |
| data[].creatureExperience | integer(int64) | Y | N | 일일 100, 주간 300 |  |
| data[].contributionScore | integer(int64) | Y | N | 일일 10/20/30, 주간 30/60/90 |  |
| data[].completed | boolean | Y | N | true/false |  |
| data[].completedAt | string(date-time) | Y | Y | 미완료 null |  |
| data[].completionId | integer(int64) | Y | Y | 미완료 null |  |
| data[].progress | object | Y | N | 진행도 값; 횟수 없는 퀘스트도 0/1로 표현 |  |
| data[].progress.current | integer | Y | N | 0 이상 |  |
| data[].progress.target | integer | Y | N | 1 이상 |  |
| data[].photoAttemptsRemaining | integer | Y | Y | 0~5; PHOTO_EXIF 외에는 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### completeQuestAssignment — `POST /api/v1/quest-assignments/{assignmentId}/completions`

- **Operation ID:** `completeQuestAssignment` · **요구사항:** `REQ-QST-002` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** Body 없음. SELF_CHECK에만 직접 완료 허용, 중복 완료 시 기존 확정 결과 재전송.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Path | assignmentId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.completionId | integer(int64) | Y | N | 1 이상 |  |
| data.assignmentId | integer(int64) | Y | N | 1 이상 |  |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.completedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.creatureExperienceAwarded | integer(int64) | Y | N | 0 이상 |  |
| data.contributionAwarded | integer(int64) | Y | N | 0 이상 |  |
| data.creatureTotalExperience | integer(int64) | Y | N | 0 이상 |  |
| data.creatureGrowthStage | string(enum) | Y | N | EGG \| HATCHLING \| GROWING \| MATURE \| COMPLETE |  |
| data.groupSeasonScore | integer(int64) | Y | N | 0 이상 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `QUEST_PERIOD_ENDED` | 퀘스트 유효 기간 밖 |
| 409 | `QUEST_COMPLETION_NOT_ALLOWED` | 해당 판정 유형 직접 완료 불가 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### createCheckIn — `POST /api/v1/check-ins`

- **Operation ID:** `createCheckIn` · **요구사항:** `REQ-QST-003` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** KST 일 1회, 05:00 이상 09:00 미만 오전 기상 판정. Body 없음.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.checkInId | integer(int64) | Y | N | 1 이상 |  |
| data.checkInDate | string(date) | Y | N | YYYY-MM-DD, KST 날짜 |  |
| data.checkedInAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.isMorning | boolean | Y | N | KST 05:00~08:59:59 |  |
| data.completedQuestIds | array<integer(int64)> | Y | N | 자동 완료된 퀘스트 완료 ID 목록, 없으면 [] |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `QUEST_PERIOD_ENDED` | 퀘스트 유효 기간 밖 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### submitQuestPhoto — `POST /api/v1/photo-submissions`

- **Operation ID:** `submitQuestPhoto` · **요구사항:** `REQ-QST-004` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** Content-Type: multipart/form-data. 배정당 최대 5회/사용자 분당 5회. EXIF 최대 1MiB, 파싱 5초.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | multipart/form-data(boundary 포함) |
| Multipart | assignmentId | integer(int64) | Y | N | 1 이상 |
| Multipart | file | file(binary) | Y | N | JPEG, Content-Type image/jpeg, 시그니처 FF D8 FF, 최대 10 MiB; EXIF GPS·촬영시각 필수 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.photoSubmissionId | integer(int64) | Y | N | 1 이상 |  |
| data.assignmentId | integer(int64) | Y | N | 1 이상 |  |
| data.attemptNumber | integer | Y | N | 1~5 |  |
| data.verificationStatus | string(enum) | Y | N | PENDING \| VALID \| INVALID |  |
| data.failureReason | string | Y | Y | EXIF_GPS_MISSING \| EXIF_GPS_OUT_OF_RANGE \| EXIF_CAPTURED_AT_MISSING \| PHOTO_CAPTURED_OUTSIDE_ASSIGNMENT_PERIOD \| PHOTO_PROCESSING_TIMEOUT \| QUEST_PERIOD_ENDED; 실패 외 null |  |
| data.uploadedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.capturedAt | string(date-time) | Y | Y | EXIF 누락 시 null |  |
| data.completionId | integer(int64) | Y | Y | 확정된 보상 없으면 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 413 | `FILE_TOO_LARGE` | 사진 10 MiB 초과 |
| 409 | `PHOTO_ATTEMPT_LIMIT_EXCEEDED` | 사진 제출 횟수 5회 초과 |
| 409 | `QUEST_PERIOD_ENDED` | 퀘스트 유효 기간 밖 |
| 429 | `RATE_LIMIT_EXCEEDED` | 호출량/동시 처리 제한 초과 |

## 6. 랭킹 API

### getMemberRanking — `GET /api/v1/rankings/members`

- **Operation ID:** `getMemberRanking` · **요구사항:** `REQ-RNK-001` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 현재 그룹 개인 랭킹, 과거 탈퇴자 포함. meta.currentSubject 제공.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | seasonId | integer(int64) | N | N | 1 이상, 미지정 현재 시즌 |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].rank | integer | Y | N | 1 이상 |  |
| data[].userId | integer(int64) | Y | N | 1 이상 |  |
| data[].nickname | string | Y | N | 2~20자 |  |
| data[].contributionScore | integer(int64) | Y | N | 0 이상 |  |
| data[].scoreReachedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].participantStatus | string(enum) | Y | N | ACTIVE \| LEFT \| REMOVED |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |
| meta.currentSubject | object | Y | Y | 대상이 없으면 null | 현재 사용자/그룹의 전체 순위 |
| meta.currentSubject.subjectId | integer(int64) | C | N | 1 이상 | 현재 사용자 ID 또는 그룹 ID |
| meta.currentSubject.rank | integer | C | Y | 1 이상 또는 순위 없음 null | 현재 순위 |
| meta.currentSubject.score | integer(int64) | C | N | 0 이상 | 현재 점수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getGroupRanking — `GET /api/v1/rankings/groups`

- **Operation ID:** `getGroupRanking` · **요구사항:** `REQ-RNK-002` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 전체 그룹 시즌 랭킹. meta.currentSubject 제공.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | seasonId | integer(int64) | N | N | 1 이상, 미지정 현재 시즌 |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].rank | integer | Y | N | 1 이상 |  |
| data[].groupId | integer(int64) | Y | N | 1 이상 |  |
| data[].groupName | string | Y | N | 2~30자 |  |
| data[].score | integer(int64) | Y | N | 0 이상 |  |
| data[].scoreReachedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |
| meta.currentSubject | object | Y | Y | 대상이 없으면 null | 현재 사용자/그룹의 전체 순위 |
| meta.currentSubject.subjectId | integer(int64) | C | N | 1 이상 | 현재 사용자 ID 또는 그룹 ID |
| meta.currentSubject.rank | integer | C | Y | 1 이상 또는 순위 없음 null | 현재 순위 |
| meta.currentSubject.score | integer(int64) | C | N | 0 이상 | 현재 점수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

## 7. 상점·아바타 API

### listShopItems — `GET /api/v1/shop-items`

- **Operation ID:** `listShopItems` · **요구사항:** `REQ-AVA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 목록은 ACTIVE 상품만 노출. 필터 slot은 이번 명세 보완안.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | slot | string(enum) | N | N | HEAD \| FACE \| BODY \| ACCESSORY; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].shopItemId | integer(int64) | Y | N | 1 이상 |  |
| data[].code | string | Y | N | 1~50자 |  |
| data[].name | string | Y | N | 1~100자 |  |
| data[].description | string | Y | N | 0~500자 |  |
| data[].priceKrw | integer(int64) | Y | N | 1 이상, 원 단위 |  |
| data[].slot | string(enum) | Y | N | HEAD \| FACE \| BODY \| ACCESSORY |  |
| data[].assetKey | string | Y | N | 1~255자 |  |
| data[].status | string(enum) | Y | N | ACTIVE \| INACTIVE |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getShopItem — `GET /api/v1/shop-items/{itemId}`

- **Operation ID:** `getShopItem` · **요구사항:** `REQ-AVA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Path | itemId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.shopItemId | integer(int64) | Y | N | 1 이상 |  |
| data.code | string | Y | N | 1~50자 |  |
| data.name | string | Y | N | 1~100자 |  |
| data.description | string | Y | N | 0~500자 |  |
| data.priceKrw | integer(int64) | Y | N | 1 이상, 원 단위 |  |
| data.slot | string(enum) | Y | N | HEAD \| FACE \| BODY \| ACCESSORY |  |
| data.assetKey | string | Y | N | 1~255자 |  |
| data.status | string(enum) | Y | N | ACTIVE \| INACTIVE |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

### listOwnedItems — `GET /api/v1/owned-items`

- **Operation ID:** `listOwnedItems` · **요구사항:** `REQ-AVA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | slot | string(enum) | N | N | HEAD \| FACE \| BODY \| ACCESSORY; 미지정 전체 |
| Query | status | string(enum) | N | N | OWNED_UNUSED \| OWNED_USED \| REVOKED; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].ownedItemId | integer(int64) | Y | N | 1 이상 |  |
| data[].purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data[].shopItemId | integer(int64) | Y | N | 1 이상 |  |
| data[].name | string | Y | N | 1~100자 |  |
| data[].slot | string(enum) | Y | N | HEAD \| FACE \| BODY \| ACCESSORY |  |
| data[].assetKey | string | Y | N | 1~255자 |  |
| data[].status | string(enum) | Y | N | OWNED_UNUSED \| OWNED_USED \| REVOKED |  |
| data[].usedAt | string(date-time) | Y | Y | 미사용이면 null |  |
| data[].revokedAt | string(date-time) | Y | Y | 회수 전 null |  |
| data[].equipped | boolean | Y | N | 현재 장착 상태 |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getAvatar — `GET /api/v1/avatar`

- **Operation ID:** `getAvatar` · **요구사항:** `REQ-AVA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.equipments | object | Y | N | 슬롯 네 개 모두 포함 |  |
| data.equipments.HEAD | object | Y | Y | 미장착이면 null |  |
| data.equipments.HEAD.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.HEAD.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.HEAD.name | string | C | N | 1~100자 |  |
| data.equipments.HEAD.assetKey | string | C | N | 1~255자 |  |
| data.equipments.FACE | object | Y | Y | 미장착이면 null |  |
| data.equipments.FACE.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.FACE.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.FACE.name | string | C | N | 1~100자 |  |
| data.equipments.FACE.assetKey | string | C | N | 1~255자 |  |
| data.equipments.BODY | object | Y | Y | 미장착이면 null |  |
| data.equipments.BODY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.BODY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.BODY.name | string | C | N | 1~100자 |  |
| data.equipments.BODY.assetKey | string | C | N | 1~255자 |  |
| data.equipments.ACCESSORY | object | Y | Y | 미장착이면 null |  |
| data.equipments.ACCESSORY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.ACCESSORY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.ACCESSORY.name | string | C | N | 1~100자 |  |
| data.equipments.ACCESSORY.assetKey | string | C | N | 1~255자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### equipAvatarItem — `PUT /api/v1/avatar/equipments/{slot}`

- **Operation ID:** `equipAvatarItem` · **요구사항:** `REQ-AVA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | slot | string(enum) | Y | N | HEAD \| FACE \| BODY \| ACCESSORY |
| Body | ownedItemId | integer(int64) | Y | N | 1 이상; 본인 보유, 슬롯 일치 |
| Body | confirmFirstUse | boolean | N | N | 미전송 시 false; 최초 장착일 때 true 필수 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.equipments | object | Y | N | 슬롯 네 개 모두 포함 |  |
| data.equipments.HEAD | object | Y | Y | 미장착이면 null |  |
| data.equipments.HEAD.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.HEAD.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.HEAD.name | string | C | N | 1~100자 |  |
| data.equipments.HEAD.assetKey | string | C | N | 1~255자 |  |
| data.equipments.FACE | object | Y | Y | 미장착이면 null |  |
| data.equipments.FACE.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.FACE.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.FACE.name | string | C | N | 1~100자 |  |
| data.equipments.FACE.assetKey | string | C | N | 1~255자 |  |
| data.equipments.BODY | object | Y | Y | 미장착이면 null |  |
| data.equipments.BODY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.BODY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.BODY.name | string | C | N | 1~100자 |  |
| data.equipments.BODY.assetKey | string | C | N | 1~255자 |  |
| data.equipments.ACCESSORY | object | Y | Y | 미장착이면 null |  |
| data.equipments.ACCESSORY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.ACCESSORY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.ACCESSORY.name | string | C | N | 1~100자 |  |
| data.equipments.ACCESSORY.assetKey | string | C | N | 1~255자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 400 | `SLOT_MISMATCH` | 보유 아이템과 장착 슬롯 불일치 |
| 409 | `FIRST_USE_CONFIRMATION_REQUIRED` | 최초 장착 동의 필요 |
| 409 | `REFUND_NOT_ALLOWED_USED_ITEM` | 사용된 아이템 환불 불가 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### unequipAvatarItem — `DELETE /api/v1/avatar/equipments/{slot}`

- **Operation ID:** `unequipAvatarItem` · **요구사항:** `REQ-AVA-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Path | slot | string(enum) | Y | N | HEAD \| FACE \| BODY \| ACCESSORY |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.userId | integer(int64) | Y | N | 1 이상 |  |
| data.equipments | object | Y | N | 슬롯 네 개 모두 포함 |  |
| data.equipments.HEAD | object | Y | Y | 미장착이면 null |  |
| data.equipments.HEAD.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.HEAD.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.HEAD.name | string | C | N | 1~100자 |  |
| data.equipments.HEAD.assetKey | string | C | N | 1~255자 |  |
| data.equipments.FACE | object | Y | Y | 미장착이면 null |  |
| data.equipments.FACE.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.FACE.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.FACE.name | string | C | N | 1~100자 |  |
| data.equipments.FACE.assetKey | string | C | N | 1~255자 |  |
| data.equipments.BODY | object | Y | Y | 미장착이면 null |  |
| data.equipments.BODY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.BODY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.BODY.name | string | C | N | 1~100자 |  |
| data.equipments.BODY.assetKey | string | C | N | 1~255자 |  |
| data.equipments.ACCESSORY | object | Y | Y | 미장착이면 null |  |
| data.equipments.ACCESSORY.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.ACCESSORY.shopItemId | integer(int64) | C | N | 1 이상 |  |
| data.equipments.ACCESSORY.name | string | C | N | 1~100자 |  |
| data.equipments.ACCESSORY.assetKey | string | C | N | 1~255자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

## 8. 결제·환불 API

### createPurchase — `POST /api/v1/purchases`

- **Operation ID:** `createPurchase` · **요구사항:** `REQ-PAY-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `201`
- **엔드포인트 조건:** 장바구니 없이 주문당 상품 1개. 서버 가격을 기준으로 PortOne V2 결제 설정 반환.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Header | Idempotency-Key | string(uuid) | Y | N | 표준 UUID, 필수 |
| Body | shopItemId | integer(int64) | Y | N | 1 이상; ACTIVE 상품 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.orderNumber | string(uuid) | Y | N | UUID 36자 = PG paymentId |  |
| data.shopItemId | integer(int64) | Y | N | 1 이상 |  |
| data.itemName | string | Y | N | 1~100자 |  |
| data.totalAmount | integer(int64) | Y | N | 1 이상, 서버 확정 가격 |  |
| data.currency | string(enum) | Y | N | KRW |  |
| data.payment | object | Y | N | 포트원 V2 클라이언트 공개 설정만 포함 |  |
| data.payment.storeId | string | Y | N | 1자 이상, 공개값 |  |
| data.payment.channelKey | string | Y | N | 1자 이상, 공개값 |  |
| data.payment.paymentId | string(uuid) | Y | N | orderNumber와 동일 |  |
| data.payment.orderName | string | Y | N | 1~100자 |  |
| data.payment.totalAmount | integer(int64) | Y | N | totalAmount와 동일 |  |
| data.payment.currency | string(enum) | Y | N | KRW |  |
| data.payment.payMethod | string(enum) | Y | N | EASY_PAY |  |
| data.payment.easyPay | object | Y | N | 필수 |  |
| data.payment.easyPay.easyPayProvider | string(enum) | Y | N | KAKAOPAY |  |
| data.payment.windowType | object | Y | N | 필수 |  |
| data.payment.windowType.pc | string(enum) | Y | N | IFRAME |  |
| data.payment.windowType.mobile | string(enum) | Y | N | REDIRECTION |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 Payload 전달 |
| 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 같은 멱등 키 처리 중 |

### verifyPurchasePayment — `POST /api/v1/purchases/{purchaseId}/payment-verifications`

- **Operation ID:** `verifyPurchasePayment` · **요구사항:** `REQ-PAY-001` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 브라우저 전달 값만으로 확정 금지; 포트원 최신 거래 조회 후 지급 확정.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | purchaseId | integer(int64) | Y | N | 1 이상 |
| Body | paymentId | string(uuid) | Y | N | UUID 36자; 해당 구매 orderNumber와 일치 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data.itemName | string | Y | N | 1~100자 |  |
| data.priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data.currency | string(enum) | Y | N | KRW |  |
| data.status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.ownedItemStatus | string(enum) | Y | Y | OWNED_UNUSED \| OWNED_USED \| REVOKED; 미지급 null |  |
| data.latestRefundStatus | string(enum) | Y | Y | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED; 요청 없으면 null |  |
| data.shopItemId | integer(int64) | Y | N | 1 이상 |  |
| data.transactions | array<object> | Y | N | 미거래면 [] |  |
| data.transactions[].transactionId | integer(int64) | Y | N | 1 이상 |  |
| data.transactions[].provider | string(enum) | Y | N | PORTONE |  |
| data.transactions[].providerTransactionId | string | Y | Y | 승인/취소 거래 확정 전 null |  |
| data.transactions[].transactionType | string(enum) | Y | N | APPROVAL \| CANCELLATION |  |
| data.transactions[].amountKrw | integer(int64) | Y | N | 0 이상 |  |
| data.transactions[].status | string(enum) | Y | N | REQUESTED \| SUCCESS \| DEFINITIVE_FAILURE \| UNKNOWN |  |
| data.transactions[].processedAt | string(date-time) | Y | Y | 미확정이면 null |  |
| data.refundRequests | array<object> | Y | N | 없으면 [] |  |
| data.refundRequests[].refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.refundRequests[].purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.refundRequests[].requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.refundRequests[].requestReason | string | Y | N | 1~500자 |  |
| data.refundRequests[].decisionReason | string | Y | Y | 처리 전 null |  |
| data.refundRequests[].status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.refundRequests[].requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.refundRequests[].reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.refundRequests[].completedAt | string(date-time) | Y | Y | 완료 전 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `PAYMENT_AMOUNT_MISMATCH` | PG 실제 금액과 주문 금액 불일치 |
| 409 | `PAYMENT_PENDING` | 결제 상태 미확정 |
| 409 | `PAYMENT_RESULT_UNKNOWN` | 결제 최신 상태 확인 실패 |

### listPurchases — `GET /api/v1/purchases`

- **Operation ID:** `listPurchases` · **요구사항:** `REQ-PAY-001, REQ-PAY-002` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | status | string(enum) | N | N | READY \| PAID \| FAILED \| CANCELLED; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data[].orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data[].itemName | string | Y | N | 1~100자 |  |
| data[].priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data[].currency | string(enum) | Y | N | KRW |  |
| data[].status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data[].createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].ownedItemStatus | string(enum) | Y | Y | OWNED_UNUSED \| OWNED_USED \| REVOKED; 미지급 null |  |
| data[].latestRefundStatus | string(enum) | Y | Y | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED; 요청 없으면 null |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getPurchase — `GET /api/v1/purchases/{purchaseId}`

- **Operation ID:** `getPurchase` · **요구사항:** `REQ-PAY-001, REQ-PAY-002` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Path | purchaseId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data.itemName | string | Y | N | 1~100자 |  |
| data.priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data.currency | string(enum) | Y | N | KRW |  |
| data.status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.ownedItemStatus | string(enum) | Y | Y | OWNED_UNUSED \| OWNED_USED \| REVOKED; 미지급 null |  |
| data.latestRefundStatus | string(enum) | Y | Y | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED; 요청 없으면 null |  |
| data.shopItemId | integer(int64) | Y | N | 1 이상 |  |
| data.transactions | array<object> | Y | N | 미거래면 [] |  |
| data.transactions[].transactionId | integer(int64) | Y | N | 1 이상 |  |
| data.transactions[].provider | string(enum) | Y | N | PORTONE |  |
| data.transactions[].providerTransactionId | string | Y | Y | 승인/취소 거래 확정 전 null |  |
| data.transactions[].transactionType | string(enum) | Y | N | APPROVAL \| CANCELLATION |  |
| data.transactions[].amountKrw | integer(int64) | Y | N | 0 이상 |  |
| data.transactions[].status | string(enum) | Y | N | REQUESTED \| SUCCESS \| DEFINITIVE_FAILURE \| UNKNOWN |  |
| data.transactions[].processedAt | string(date-time) | Y | Y | 미확정이면 null |  |
| data.refundRequests | array<object> | Y | N | 없으면 [] |  |
| data.refundRequests[].refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.refundRequests[].purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.refundRequests[].requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.refundRequests[].requestReason | string | Y | N | 1~500자 |  |
| data.refundRequests[].decisionReason | string | Y | Y | 처리 전 null |  |
| data.refundRequests[].status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.refundRequests[].requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.refundRequests[].reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.refundRequests[].completedAt | string(date-time) | Y | Y | 완료 전 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

### receivePortOneWebhook — `POST /api/v1/payments/webhooks/portone`

- **Operation ID:** `receivePortOneWebhook` · **요구사항:** `REQ-PAY-001, REQ-PAY-002` · **인증·권한:** `PORTONE_SIGNATURE` · **성공 HTTP:** `202`
- **엔드포인트 조건:** 서명 검증은 JSON 파싱 전 원본 바이트 기준. 외부 공급자는 미지의 필드를 추가할 수 있으므로 이 Operation만 미지 필드 허용. 서명 오류는 400 빈 Body; 성공은 202 data={}. 공식 문서 https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | webhook-id | string | Y | N | PortOne Standard Webhooks ID |
| Header | webhook-timestamp | string | Y | N | PortOne UNIX timestamp 문자열 |
| Header | webhook-signature | string | Y | N | 서명 형식은 PortOne 명세 준수 |
| Body | type | string | Y | N | Transaction.Paid \| Transaction.Cancelled \| Transaction.CancelPending \| Transaction.Failed 등; 알 수 없는 type 무시 |
| Body | timestamp | string(date-time) | Y | N | RFC3339 |
| Body | data | object | Y | N | 공급자 이벤트에 따라 필드 상이 |
| Body | data.storeId | string | Y | N | 공급자 상점 ID |
| Body | data.paymentId | string | C | N | 해당 결제의 고유 번호; 결제 이벤트에서 필수 |
| Body | data.transactionId | string | C | N | 해당 결제의 시도 번호 |
| Body | data.cancellationId | string | C | N | 취소 이벤트에서 필수 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |

**오류 응답**

서명 실패 및 변조된 전송은 `400` **빈 응답 본문**. 검증 후 알 수 없는 `type`, 재수신, 미등록 주문은 `202` + `{"data":{}}`; 검증된 이벤트의 결제 상태는 포트원 최신 조회 후 반영합니다. 공급자 서버 오류 시 `5xx` 가능.

### createRefundRequest — `POST /api/v1/purchases/{purchaseId}/refund-requests`

- **Operation ID:** `createRefundRequest` · **요구사항:** `REQ-PAY-002` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `201`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Header | Idempotency-Key | string(uuid) | Y | N | 표준 UUID, 필수 |
| Path | purchaseId | integer(int64) | Y | N | 1 이상 |
| Body | reason | string | Y | N | trim 후 1~500자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.requestReason | string | Y | N | 1~500자 |  |
| data.decisionReason | string | Y | Y | 처리 전 null |  |
| data.status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.completedAt | string(date-time) | Y | Y | 완료 전 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `REFUND_NOT_ALLOWED_USED_ITEM` | 사용된 아이템 환불 불가 |
| 409 | `REFUND_ALREADY_ACTIVE` | 진행 중 환불이 존재 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 Payload 전달 |

### listMyRefundRequests — `GET /api/v1/refund-requests`

- **Operation ID:** `listMyRefundRequests` · **요구사항:** `REQ-PAY-002` · **인증·권한:** `AUTHENTICATED` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | status | string(enum) | N | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data[].purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data[].requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data[].requestReason | string | Y | N | 1~500자 |  |
| data[].decisionReason | string | Y | Y | 처리 전 null |  |
| data[].status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data[].requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data[].completedAt | string(date-time) | Y | Y | 완료 전 null |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### listAdminRefundRequests — `GET /api/v1/admin/refund-requests`

- **Operation ID:** `listAdminRefundRequests` · **요구사항:** `REQ-ADM-003` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | status | string(enum) | N | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data[].purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data[].requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data[].requestReason | string | Y | N | 1~500자 |  |
| data[].decisionReason | string | Y | Y | 처리 전 null |  |
| data[].status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data[].requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data[].completedAt | string(date-time) | Y | Y | 완료 전 null |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getAdminRefundRequest — `GET /api/v1/admin/refund-requests/{requestId}`

- **Operation ID:** `getAdminRefundRequest` · **요구사항:** `REQ-ADM-003` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Path | requestId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.requestReason | string | Y | N | 1~500자 |  |
| data.decisionReason | string | Y | Y | 처리 전 null |  |
| data.status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.completedAt | string(date-time) | Y | Y | 완료 전 null |  |
| data.reviewedByUserId | integer(int64) | Y | Y | 미심사 null |  |
| data.purchase | object | Y | N | 필수 |  |
| data.purchase.orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data.purchase.itemName | string | Y | N | 1~100자 |  |
| data.purchase.priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data.purchase.status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data.ownedItem | object | Y | Y | 미지급이면 null |  |
| data.ownedItem.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.ownedItem.status | string(enum) | C | N | OWNED_UNUSED \| OWNED_USED \| REVOKED |  |
| data.ownedItem.usedAt | string(date-time) | C | Y | 미사용 null |  |
| data.latestCancellation | object | Y | Y | 취소 시도 전 null |  |
| data.latestCancellation.providerTransactionId | string | C | Y | 미확정 null |  |
| data.latestCancellation.status | string(enum) | C | N | REQUESTED \| SUCCESS \| DEFINITIVE_FAILURE \| UNKNOWN |  |
| data.latestCancellation.processedAt | string(date-time) | C | Y | 미확정 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

### approveRefund — `POST /api/v1/admin/refund-requests/{requestId}/approvals`

- **Operation ID:** `approveRefund` · **요구사항:** `REQ-ADM-003` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 승인 시 PG 취소 결과가 UNKNOWN이면 성공/실패를 추정하지 않는다.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Header | Idempotency-Key | string(uuid) | Y | N | 표준 UUID, 필수 |
| Path | requestId | integer(int64) | Y | N | 1 이상 |
| Body | decisionReason | string | Y | N | trim 후 1~500자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.requestReason | string | Y | N | 1~500자 |  |
| data.decisionReason | string | Y | Y | 처리 전 null |  |
| data.status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.completedAt | string(date-time) | Y | Y | 완료 전 null |  |
| data.reviewedByUserId | integer(int64) | Y | Y | 미심사 null |  |
| data.purchase | object | Y | N | 필수 |  |
| data.purchase.orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data.purchase.itemName | string | Y | N | 1~100자 |  |
| data.purchase.priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data.purchase.status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data.ownedItem | object | Y | Y | 미지급이면 null |  |
| data.ownedItem.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.ownedItem.status | string(enum) | C | N | OWNED_UNUSED \| OWNED_USED \| REVOKED |  |
| data.ownedItem.usedAt | string(date-time) | C | Y | 미사용 null |  |
| data.latestCancellation | object | Y | Y | 취소 시도 전 null |  |
| data.latestCancellation.providerTransactionId | string | C | Y | 미확정 null |  |
| data.latestCancellation.status | string(enum) | C | N | REQUESTED \| SUCCESS \| DEFINITIVE_FAILURE \| UNKNOWN |  |
| data.latestCancellation.processedAt | string(date-time) | C | Y | 미확정 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `REFUND_NOT_ALLOWED_USED_ITEM` | 사용된 아이템 환불 불가 |
| 409 | `PG_CANCEL_RESULT_UNKNOWN` | PG 취소 결과 불명 |
| 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 같은 멱등 키 처리 중 |

### rejectRefund — `POST /api/v1/admin/refund-requests/{requestId}/rejections`

- **Operation ID:** `rejectRefund` · **요구사항:** `REQ-ADM-003` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | requestId | integer(int64) | Y | N | 1 이상 |
| Body | decisionReason | string | Y | N | trim 후 1~500자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.requestReason | string | Y | N | 1~500자 |  |
| data.decisionReason | string | Y | Y | 처리 전 null |  |
| data.status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.completedAt | string(date-time) | Y | Y | 완료 전 null |  |
| data.reviewedByUserId | integer(int64) | Y | Y | 미심사 null |  |
| data.purchase | object | Y | N | 필수 |  |
| data.purchase.orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data.purchase.itemName | string | Y | N | 1~100자 |  |
| data.purchase.priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data.purchase.status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data.ownedItem | object | Y | Y | 미지급이면 null |  |
| data.ownedItem.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.ownedItem.status | string(enum) | C | N | OWNED_UNUSED \| OWNED_USED \| REVOKED |  |
| data.ownedItem.usedAt | string(date-time) | C | Y | 미사용 null |  |
| data.latestCancellation | object | Y | Y | 취소 시도 전 null |  |
| data.latestCancellation.providerTransactionId | string | C | Y | 미확정 null |  |
| data.latestCancellation.status | string(enum) | C | N | REQUESTED \| SUCCESS \| DEFINITIVE_FAILURE \| UNKNOWN |  |
| data.latestCancellation.processedAt | string(date-time) | C | Y | 미확정 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### reconcileRefund — `POST /api/v1/admin/refund-requests/{requestId}/reconciliations`

- **Operation ID:** `reconcileRefund` · **요구사항:** `REQ-ADM-003` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`
- **엔드포인트 조건:** PG 취소 상태 재조회 및 정합성 복구. 결과 불명 시 재취소 자동 실행 금지.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Idempotency-Key | string(uuid) | Y | N | 표준 UUID, 필수 |
| Path | requestId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.refundRequestId | integer(int64) | Y | N | 1 이상 |  |
| data.purchaseId | integer(int64) | Y | N | 1 이상 |  |
| data.requestedByUserId | integer(int64) | Y | N | 1 이상 |  |
| data.requestReason | string | Y | N | 1~500자 |  |
| data.decisionReason | string | Y | Y | 처리 전 null |  |
| data.status | string(enum) | Y | N | REQUESTED \| REJECTED \| CANCEL_PENDING \| CANCEL_UNKNOWN \| COMPLETED \| FAILED |  |
| data.requestedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.reviewedAt | string(date-time) | Y | Y | 미심사 null |  |
| data.completedAt | string(date-time) | Y | Y | 완료 전 null |  |
| data.reviewedByUserId | integer(int64) | Y | Y | 미심사 null |  |
| data.purchase | object | Y | N | 필수 |  |
| data.purchase.orderNumber | string(uuid) | Y | N | UUID 36자 |  |
| data.purchase.itemName | string | Y | N | 1~100자 |  |
| data.purchase.priceKrw | integer(int64) | Y | N | 1 이상 |  |
| data.purchase.status | string(enum) | Y | N | READY \| PAID \| FAILED \| CANCELLED |  |
| data.ownedItem | object | Y | Y | 미지급이면 null |  |
| data.ownedItem.ownedItemId | integer(int64) | C | N | 1 이상 |  |
| data.ownedItem.status | string(enum) | C | N | OWNED_UNUSED \| OWNED_USED \| REVOKED |  |
| data.ownedItem.usedAt | string(date-time) | C | Y | 미사용 null |  |
| data.latestCancellation | object | Y | Y | 취소 시도 전 null |  |
| data.latestCancellation.providerTransactionId | string | C | Y | 미확정 null |  |
| data.latestCancellation.status | string(enum) | C | N | REQUESTED \| SUCCESS \| DEFINITIVE_FAILURE \| UNKNOWN |  |
| data.latestCancellation.processedAt | string(date-time) | C | Y | 미확정 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `PG_CANCEL_RESULT_UNKNOWN` | PG 취소 결과 불명 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 Payload 전달 |

## 9. 신고·관리자 API

### createUserReport — `POST /api/v1/reports`

- **Operation ID:** `createUserReport` · **요구사항:** `REQ-GRP-006` · **인증·권한:** `GROUP_MEMBER` · **성공 HTTP:** `201`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Body | reportedUserId | integer(int64) | Y | N | 1 이상; 자신 신고 불가 |
| Body | category | string(enum) | Y | N | HARASSMENT \| SPAM \| INAPPROPRIATE_CONTENT \| OTHER |
| Body | content | string | Y | N | trim 후 1~1000자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.reportId | integer(int64) | Y | N | 1 이상 |  |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.reporterUserId | integer(int64) | Y | N | 1 이상 |  |
| data.reportedUserId | integer(int64) | Y | N | 1 이상 |  |
| data.category | string(enum) | Y | N | HARASSMENT \| SPAM \| INAPPROPRIATE_CONTENT \| OTHER |  |
| data.content | string | Y | N | 1~1000자 |  |
| data.status | string(enum) | Y | N | RECEIVED \| RESOLVED \| REJECTED |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.handledAt | string(date-time) | Y | Y | 미처리 null |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

### listAdminReports — `GET /api/v1/admin/reports`

- **Operation ID:** `listAdminReports` · **요구사항:** `REQ-ADM-002` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | status | string(enum) | N | N | RECEIVED \| RESOLVED \| REJECTED; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].reportId | integer(int64) | Y | N | 1 이상 |  |
| data[].groupId | integer(int64) | Y | N | 1 이상 |  |
| data[].reporterUserId | integer(int64) | Y | N | 1 이상 |  |
| data[].reportedUserId | integer(int64) | Y | N | 1 이상 |  |
| data[].category | string(enum) | Y | N | HARASSMENT \| SPAM \| INAPPROPRIATE_CONTENT \| OTHER |  |
| data[].content | string | Y | N | 1~1000자 |  |
| data[].status | string(enum) | Y | N | RECEIVED \| RESOLVED \| REJECTED |  |
| data[].createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data[].handledAt | string(date-time) | Y | Y | 미처리 null |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### getAdminReport — `GET /api/v1/admin/reports/{reportId}`

- **Operation ID:** `getAdminReport` · **요구사항:** `REQ-ADM-002` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Path | reportId | integer(int64) | Y | N | 1 이상 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.reportId | integer(int64) | Y | N | 1 이상 |  |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.reporterUserId | integer(int64) | Y | N | 1 이상 |  |
| data.reportedUserId | integer(int64) | Y | N | 1 이상 |  |
| data.category | string(enum) | Y | N | HARASSMENT \| SPAM \| INAPPROPRIATE_CONTENT \| OTHER |  |
| data.content | string | Y | N | 1~1000자 |  |
| data.status | string(enum) | Y | N | RECEIVED \| RESOLVED \| REJECTED |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.handledAt | string(date-time) | Y | Y | 미처리 null |  |
| data.reportedUserNickname | string | Y | N | 2~20자 |  |
| data.reporterNickname | string | Y | N | 2~20자 |  |
| data.handledByUserId | integer(int64) | Y | Y | 미처리 null |  |
| data.resolution | string | Y | Y | 미처리 null / 처리 사유 1~1000자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |

### dismissReport — `POST /api/v1/admin/reports/{reportId}/dismissals`

- **Operation ID:** `dismissReport` · **요구사항:** `REQ-ADM-002` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | reportId | integer(int64) | Y | N | 1 이상 |
| Body | reason | string | Y | N | trim 후 1~1000자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.reportId | integer(int64) | Y | N | 1 이상 |  |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.reporterUserId | integer(int64) | Y | N | 1 이상 |  |
| data.reportedUserId | integer(int64) | Y | N | 1 이상 |  |
| data.category | string(enum) | Y | N | HARASSMENT \| SPAM \| INAPPROPRIATE_CONTENT \| OTHER |  |
| data.content | string | Y | N | 1~1000자 |  |
| data.status | string(enum) | Y | N | RECEIVED \| RESOLVED \| REJECTED |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.handledAt | string(date-time) | Y | Y | 미처리 null |  |
| data.reportedUserNickname | string | Y | N | 2~20자 |  |
| data.reporterNickname | string | Y | N | 2~20자 |  |
| data.handledByUserId | integer(int64) | Y | Y | 미처리 null |  |
| data.resolution | string | Y | Y | 미처리 null / 처리 사유 1~1000자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### removeReportedMember — `POST /api/v1/admin/reports/{reportId}/removals`

- **Operation ID:** `removeReportedMember` · **요구사항:** `REQ-GRP-006, REQ-ADM-002` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | reportId | integer(int64) | Y | N | 1 이상 |
| Body | reason | string | Y | N | trim 후 1~1000자 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.reportId | integer(int64) | Y | N | 1 이상 |  |
| data.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.reporterUserId | integer(int64) | Y | N | 1 이상 |  |
| data.reportedUserId | integer(int64) | Y | N | 1 이상 |  |
| data.category | string(enum) | Y | N | HARASSMENT \| SPAM \| INAPPROPRIATE_CONTENT \| OTHER |  |
| data.content | string | Y | N | 1~1000자 |  |
| data.status | string(enum) | Y | N | RECEIVED \| RESOLVED \| REJECTED |  |
| data.createdAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.handledAt | string(date-time) | Y | Y | 미처리 null |  |
| data.reportedUserNickname | string | Y | N | 2~20자 |  |
| data.reporterNickname | string | Y | N | 2~20자 |  |
| data.handledByUserId | integer(int64) | Y | Y | 미처리 null |  |
| data.resolution | string | Y | Y | 미처리 null / 처리 사유 1~1000자 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### transferPrimaryAdminAsSuperAdmin — `POST /api/v1/admin/groups/{groupId}/primary-transfer`

- **Operation ID:** `transferPrimaryAdminAsSuperAdmin` · **요구사항:** `REQ-GRP-004, REQ-ADM-004` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`
- **엔드포인트 조건:** reason 필수. 대상은 동일 그룹의 활성 회원.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Header | X-CSRF-TOKEN | string | Y | N | 현재 세션에서 발급된 CSRF 토큰 |
| Header | Content-Type | string | Y | N | application/json |
| Path | groupId | integer(int64) | Y | N | 1 이상 |
| Body | targetUserId | integer(int64) | Y | N | 1 이상 |
| Body | reason | string | Y | N | trim 후 1~500자; 전체 관리자는 필수 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.group | object | Y | N | 필수 |  |
| data.group.groupId | integer(int64) | Y | N | 1 이상 |  |
| data.group.name | string | Y | N | 2~30자 |  |
| data.group.description | string | Y | N | 0~500자 |  |
| data.group.currentMemberCount | integer | Y | N | 0~20 |  |
| data.group.capacity | integer | Y | N | 고정 20 |  |
| data.group.remainingCapacity | integer | Y | N | 0~20 |  |
| data.group.joinable | boolean | Y | N | true/false |  |
| data.group.joinBlockedReason | string(enum) | Y | Y | GROUP_FULL \| JOIN_COOLDOWN_ACTIVE \| ALREADY_IN_GROUP; 가입 가능하면 null |  |
| data.primaryAdmin | object | Y | Y | 활성 그룹 필수 | 비활성 그룹에서는 null |
| data.primaryAdmin.userId | integer(int64) | C | N | 1 이상 |  |
| data.primaryAdmin.nickname | string | C | N | 2~20자 |  |
| data.myMembership | object | Y | Y | 내가 그룹 미가입이면 null |  |
| data.myMembership.membershipId | integer(int64) | C | N | 1 이상 |  |
| data.myMembership.groupId | integer(int64) | C | N | 1 이상 |  |
| data.myMembership.role | string(enum) | C | N | PRIMARY \| DEPUTY \| MEMBER |  |
| data.myMembership.status | string(enum) | C | N | ACTIVE \| LEFT \| REMOVED |  |
| data.myMembership.joinedAt | string(date-time) | C | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 403 | `CSRF_INVALID` | CSRF 토큰 없음 또는 불일치 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 요청자에게 노출하지 않는 리소스 |
| 409 | `CONCURRENT_MODIFICATION` | 동시 처리 또는 이미 변경된 리소스 |

### getAdminDashboard — `GET /api/v1/admin/dashboard`

- **Operation ID:** `getAdminDashboard` · **요구사항:** `REQ-ADM-001` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`
- **엔드포인트 조건:** Google Charts 입력이 가능한 서버 집계. 그룹 필터는 이번 명세 보완안.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | range | string(enum) | N | N | 7_DAYS \| 30_DAYS \| CURRENT_SEASON; 미지정 7_DAYS |
| Query | groupId | integer(int64) | N | N | 1 이상; 미지정 전체 그룹 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | object | Y | N | 빈 응답은 {} | 성공 DTO |
| data.range | string(enum) | Y | N | 7_DAYS \| 30_DAYS \| CURRENT_SEASON |  |
| data.groupId | integer(int64) | Y | Y | 전체 집계 null |  |
| data.aggregatedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| data.summary | object | Y | N | 필수 |  |
| data.summary.activeUsers | integer(int64) | Y | N | 0 이상 |  |
| data.summary.activeGroups | integer(int64) | Y | N | 0 이상 |  |
| data.summary.questCompletionRate | number | Y | N | 0~100, % |  |
| data.summary.operationalWarningCount | integer(int64) | Y | N | 0 이상 |  |
| data.activitySeries | array<object> | Y | N | 날짜 오름차순, 없으면 [] |  |
| data.activitySeries[].date | string(date) | Y | N | YYYY-MM-DD, KST |  |
| data.activitySeries[].activeUsers | integer | Y | N | 0 이상 |  |
| data.activitySeries[].questCompletions | integer | Y | N | 0 이상 |  |
| data.growthStages | array<object> | Y | N | 없으면 [] |  |
| data.growthStages[].stage | string(enum) | Y | N | EGG \| HATCHLING \| GROWING \| MATURE \| COMPLETE |  |
| data.growthStages[].count | integer | Y | N | 0 이상 |  |
| data.topGroups | array<object> | Y | N | 최대 10개 |  |
| data.topGroups[].groupId | integer(int64) | Y | N | 1 이상 |  |
| data.topGroups[].groupName | string | Y | N | 2~30자 |  |
| data.topGroups[].score | integer(int64) | Y | N | 0 이상 |  |
| data.paymentSeries | array<object> | Y | N | 기간 순, 없으면 [] |  |
| data.paymentSeries[].date | string(date) | Y | N | YYYY-MM-DD, KST |  |
| data.paymentSeries[].paidAmountKrw | integer(int64) | Y | N | 0 이상 |  |
| data.paymentSeries[].refundedAmountKrw | integer(int64) | Y | N | 0 이상 |  |
| data.capacityUsage | object | Y | N | 필수 |  |
| data.capacityUsage.occupied | integer(int64) | Y | N | 0 이상 |  |
| data.capacityUsage.remaining | integer(int64) | Y | N | 0 이상 |  |
| data.queues | object | Y | N | 필수 |  |
| data.queues.pendingReports | integer | Y | N | 0 이상 |  |
| data.queues.pendingRefunds | integer | Y | N | 0 이상 |  |
| data.queues.operationalWarnings | array<object> | Y | N | 최근 10건 이하 |  |
| data.queues.operationalWarnings[].warningKey | string | Y | N | 1~255자; 유형+원본 식별자 결합, 동일 상태에서 안정적 |  |
| data.queues.operationalWarnings[].type | string(enum) | Y | N | SCHEDULER_FAILED \| TEMPLATE_SHORTAGE \| MEMBER_COUNT_MISMATCH \| PAYMENT_UNCERTAIN \| REFUND_UNCERTAIN \| PHOTO_DELETION_FAILED |  |
| data.queues.operationalWarnings[].severity | string(enum) | Y | N | WARNING \| CRITICAL |  |
| data.queues.operationalWarnings[].resourceType | string | Y | N | SEASON \| QUEST_PERIOD \| GROUP \| PURCHASE \| REFUND_REQUEST \| PHOTO_SUBMISSION |  |
| data.queues.operationalWarnings[].resourceId | integer(int64) | Y | N | 1 이상 |  |
| data.queues.operationalWarnings[].message | string | Y | N | 1~1000자 |  |
| data.queues.operationalWarnings[].detectedAt | string(date-time) | Y | N | UTC ISO 8601 |  |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

### listOperationalWarnings — `GET /api/v1/admin/operations/warnings`

- **Operation ID:** `listOperationalWarnings` · **요구사항:** `REQ-ADM-004` · **인증·권한:** `SUPER_ADMIN` · **성공 HTTP:** `200`
- **엔드포인트 조건:** 현재 원본에서 집계한 미해결 경고만 반환. 별도 읽음/해결 상태 없음.

**요청 필드**

| 위치 | 필드명 | 타입 | 필수 | null | 검증·기본값 |
|---|---|---|---|---|---|
| Header | Cookie | string | Y | N | Spring Session 세션 쿠키; HttpOnly / SameSite=Lax |
| Query | page | integer | N | N | 0 이상, 미지정 0; 0-based page |
| Query | size | integer | N | N | 1~50, 미지정 20; 페이지 크기 |
| Query | type | string(enum) | N | N | SCHEDULER_FAILED \| TEMPLATE_SHORTAGE \| MEMBER_COUNT_MISMATCH \| PAYMENT_UNCERTAIN \| REFUND_UNCERTAIN \| PHOTO_DELETION_FAILED; 미지정 전체 |

**성공 응답 필드**

| 응답 경로 | JSON 타입 | 필수 | null | 값·범위 | 의미 |
|---|---|---|---|---|---|
| data | array<object> | Y | N | 0건이면 [] | 목록 |
| data[].warningKey | string | Y | N | 1~255자; 유형+원본 식별자 결합, 동일 상태에서 안정적 |  |
| data[].type | string(enum) | Y | N | SCHEDULER_FAILED \| TEMPLATE_SHORTAGE \| MEMBER_COUNT_MISMATCH \| PAYMENT_UNCERTAIN \| REFUND_UNCERTAIN \| PHOTO_DELETION_FAILED |  |
| data[].severity | string(enum) | Y | N | WARNING \| CRITICAL |  |
| data[].resourceType | string | Y | N | SEASON \| QUEST_PERIOD \| GROUP \| PURCHASE \| REFUND_REQUEST \| PHOTO_SUBMISSION |  |
| data[].resourceId | integer(int64) | Y | N | 1 이상 |  |
| data[].message | string | Y | N | 1~1000자 |  |
| data[].detectedAt | string(date-time) | Y | N | UTC ISO 8601 |  |
| meta | object | Y | N | — | 목록 부가 정보 |
| meta.page | integer | Y | N | 0 이상 | 현재 페이지 |
| meta.size | integer | Y | N | 1~50 | 요청 페이지 크기 |
| meta.totalElements | integer(int64) | Y | N | 0 이상 | 전체 개수 |
| meta.totalPages | integer | Y | N | 0 이상 | 전체 페이지 수 |

**오류 응답**

| HTTP | code | 조건 |
|---|---|---|
| 401 | `AUTH_REQUIRED` | 세션 인증 없음 |
| 401 | `SESSION_EXPIRED` | 인증 세션 만료 |
| 403 | `ROLE_FORBIDDEN` | 요청자 권한 부족 |
| 400 | `INVALID_FIELD_VALUE` | 필드 타입·길이·값·형식 검증 실패 |
| 400 | `INVALID_REQUEST` | JSON 형식/인코딩/필수 객체/중복 또는 미등록 입력 |

## 10. WebSocket 이벤트 계약

### 10.1. 연결·구독·메시지 공통 형식

| 항목 | 계약 |
|---|---|
| 연결 | `/ws` STOMP WebSocket, Spring Session 쿠키 인증 / Origin·CSRF 검사 |
| 수신 목적지 | `/topic/groups/{groupId}/live` — 현재 활성 그룹 ID만 SUBSCRIBE 가능 |
| 권한 | `GROUP_MEMBER`; 다른 그룹 구독은 STOMP ERROR 후 거부 |
| 허용 프레임 | 클라이언트 CONNECT·자기 그룹 SUBSCRIBE·자기 구독 UNSUBSCRIBE·DISCONNECT |
| 금지 프레임 | 클라이언트 SEND/MESSAGE 명령 전부 거부; 상태 변경은 REST만 가능 |
| 재연결 | 항상 `GET /api/v1/groups/me/creature-home` Snapshot 재조회 |
| 이벤트 전파 | Commit 완료 후에만 발행; Event 중복은 eventId로 무시 |
| Presence | 단일 백엔드 인스턴스 메모리; 실시간 좌표/채팅 데이터 없음 |

**모든 서버 → 클라이언트 이벤트 Envelope**

| 필드 | 타입 | 필수 | null | 제약 |
|---|---|---|---|---|
| eventId | string(uuid) | Y | N | UUID 36자, 이벤트 중복 제거 식별자 [보완안] |
| eventType | string(enum) | Y | N | 아래 4종 [보완안] |
| groupId | integer(int64) | Y | N | 1 이상, 구독 그룹과 일치 |
| occurredAt | string(date-time) | Y | N | UTC ISO 8601 |
| payload | object | Y | N | 이벤트별 Payload 스키마 |

### 10.2. `PRESENCE_JOINED`

| payload 경로 | 타입 | 필수 | 제약/nullable |
|---|---|---|---|
| payload.userId | integer(int64) | Y | 1 이상 |
| payload.nickname | string | Y | 2~20자 |
| payload.rank | integer | Y | 1 이상 또는 null |
| payload.contributionScore | integer(int64) | Y | 0 이상 |
| payload.equipments | object | Y | AvatarSlots: HEAD/FACE/BODY/ACCESSORY 각각 객체 또는 null |

### 10.2. `PRESENCE_LEFT`

| payload 경로 | 타입 | 필수 | 제약/nullable |
|---|---|---|---|
| payload.userId | integer(int64) | Y | 1 이상 |

### 10.2. `AVATAR_EQUIPMENT_CHANGED`

| payload 경로 | 타입 | 필수 | 제약/nullable |
|---|---|---|---|
| payload.userId | integer(int64) | Y | 1 이상 |
| payload.slot | string(enum) | Y | HEAD \| FACE \| BODY \| ACCESSORY |
| payload.equippedItem | object | Y | 해제면 null, 장착이면 ownedItemId/shopItemId/name/assetKey |
| payload.equippedItem.ownedItemId | integer(int64) | C | 1 이상 |
| payload.equippedItem.shopItemId | integer(int64) | C | 1 이상 |
| payload.equippedItem.name | string | C | 1~100자 |
| payload.equippedItem.assetKey | string | C | 1~255자 |

### 10.2. `CREATURE_UPDATED`

| payload 경로 | 타입 | 필수 | 제약/nullable |
|---|---|---|---|
| payload.seasonId | integer(int64) | Y | 1 이상 |
| payload.groupCreatureId | integer(int64) | Y | 1 이상 |
| payload.totalExperience | integer(int64) | Y | 0 이상 |
| payload.growthStage | string(enum) | Y | EGG \| HATCHLING \| GROWING \| MATURE \| COMPLETE |
| payload.creatureSpeciesId | integer(int64) | Y | 알 상태에서는 null |
| payload.lastUpdatedAt | string(date-time) | Y | UTC ISO 8601 |

**WebSocket 필드명/이벤트 종류는 기존 문서에 상세 정의가 없어 이번 재작성에서 확정 형식으로 제안한 부분입니다.** 전체 Snapshot을 실시간으로 중복 송신하지 않으며, 소켓 이벤트는 상태 갱신 힌트로 취급하고 복구는 REST Snapshot으로 수행합니다.

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "CREATURE_UPDATED",
  "groupId": 501,
  "occurredAt": "2026-11-03T06:00:00Z",
  "payload": {
    "seasonId": 1,
    "groupCreatureId": 701,
    "totalExperience": 46000,
    "growthStage": "GROWING",
    "creatureSpeciesId": 3,
    "lastUpdatedAt": "2026-11-03T06:00:00Z"
  }
}
```
