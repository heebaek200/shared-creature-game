# REST API 설계서

> 이 문서는 기존 절 번호·제목, Operation ID, 엔드포인트 및 요청 ID를 유지한 **구현용 요약 계약**입니다. 모든 필드의 상세 설명을 반복하는 대신 **구현할 기능 / 주요 규칙 / 작업 대상 / 처리 순서 / 완료 확인**을 기준으로 정리했습니다. 업무적 허용 조건은 [업무 규칙](business-rules.md)을 따릅니다.

## 0. 초보 개발자를 위한 읽는 순서

1. **9절**에서 Operation ID·Method·Path·요청/응답 DTO를 확인합니다.
2. **10~12절**에서 필드 검증·응답·Enum을 확인합니다.
3. **9.5절**에서 성공 상태·업무 오류를 확인합니다.
4. **2절**의 공통 Envelope·세션·CSRF·멱등성을 적용합니다.
5. 구현 후 정상·권한·검증·중복·경합 시나리오를 테스트합니다.

## 1. 인증 아키텍처

### 구현할 기능

백엔드가 OAuth Authorization Code를 검증하고 서버 세션으로 사용자를 인증합니다. 프론트엔드는 Full CSR이며 공급자 Token을 직접 저장하지 않습니다.

### 1.1. 로그인 흐름

#### 처리 순서

1. `createOAuthAttempt`로 일회용 `state`·`nonce`와 시작 세션 문맥을 발급합니다.
2. Google/Naver로 Redirect 후 백엔드 Callback에서 시작 세션·`state`·공급자 응답을 검증합니다.
3. 기존 계정이면 서버 세션 로그인, 신규 사용자면 **30분 가입 대기**를 만듭니다.
4. `completeSignup` 이후 그룹 소속에 따라 온보딩 경로를 선택합니다.

#### 완료 확인

세션 교차·공급자 오류·중복 Callback·가입 대기 만료가 올바르게 처리됩니다.

### 1.2. 권장 보안 정책

#### 주요 규칙

- `state` 재사용 방지, 시작 세션 SHA-256 Hash 일치, Google ID Token 서명·발급자·대상·만료·`nonce` 검증.
- 쿠키는 `HttpOnly`, `SameSite=Lax`; `Secure`는 `integration/prod`에서 필수입니다.
- Credential CORS는 명시적 Origin만 허용합니다. 상태 변경 REST 요청에는 CSRF가 필요합니다.
- 공급자 Token·인증 Code·초대 Token은 브라우저 저장소와 로그에 노출하지 않습니다.
- STOMP는 기존 세션·Origin·CSRF를 검증하고 현재 그룹 구독만 허용합니다.

### 1.3. 세션 정책

| 정책 | 값 |
|---|---|
| 저장소 | Spring Session JDBC / MySQL |
| 비활동 만료 | 7일 |
| 동시 로그인 | 사용자당 3개, 초과 시 가장 오래된 세션 만료 |
| 로그아웃 | 현재 애플리케이션 세션 무효화, 공급자 로그인 유지 |

### 1.4. 서버 세션을 사용하는 이유

브라우저가 장기 인증 토큰을 보관하지 않고, 서버에서 세션 강제 종료·권한 상태를 통제하기 위해서입니다.

### 1.5. 신규 가입 이메일과 공급자 식별자

- 신규 가입은 공급자 이메일이 필요하며 식별 키는 **공급자 + 불변 사용자 ID**입니다.
- 동일 이메일의 다른 공급자 계정은 자동 병합하지 않습니다.
- 기존 사용자는 현재 공급자 응답에서 이메일이 누락되어도 불변 ID가 일치하면 로그인합니다.

### 1.6. 공급자별 최소 프로필 정보

| 공급자 | 사용 데이터 | 불변 사용자 ID | 이메일 조건 |
|---|---|---|---|
| Google | `openid profile email` | ID Token `sub` | `email`·`email_verified=true` |
| Naver | 개발자센터 프로필 `email`, `nickname` | `/v1/nid/me`의 `id` | `email` 필수 |

### 1.7. 네이버 개발·공개 운영 조건

사전 검수 전에는 등록된 관리자·테스터 계정만 로그인 가능하며, 일반 사용자 공개 운영 전 네이버 사전 검수가 필요합니다.

## 2. 공통 REST 계약

### 2.1. 경로와 성공 응답

#### 주요 규칙

- 업무 REST Prefix는 `/api/v1`이며 OAuth Callback은 `/oauth2/callback/{provider}`입니다.
- JSON 성공 결과는 공통 `data`와 필요 시 `meta`를 사용합니다. Entity나 PG 응답을 그대로 내보내지 않습니다.
- 페이지 목록은 `meta.page`, `meta.size`, `meta.totalElements`, `meta.totalPages`; 랭킹 목록은 `meta.currentSubject`를 제공합니다.
- 결과가 없어도 배열은 `[]`, DTO의 nullable 필드는 키를 유지한 채 `null`로 반환합니다.

### 2.2. 오류 응답과 HTTP 상태

#### 주요 규칙

```json
{
  "code": "GROUP_FULL",
  "message": "그룹 정원이 마감되었습니다.",
  "fieldErrors": [],
  "traceId": "요청 추적 ID",
  "timestamp": "2026-11-02T06:38:28Z"
}
```

| HTTP | 용도 |
|---:|---|
| `400` | 형식·필드 검증 실패 |
| `401` | 인증 필요·세션 만료 |
| `403` | 권한·CSRF 실패 |
| `404` | 없거나 노출 불가한 자원 |
| `409` | 상태·정원·동시 수정·멱등 충돌 |
| `413` | 사진 파일 크기 제한 위반 |
| `429` | 호출량 제한 |
| `5xx` | 외부 서비스·서버 처리 실패 |

프론트엔드 분기는 `message`가 아닌 **`code`**로 합니다.

### 2.3. CSRF

- 세션 쿠키를 사용하는 상태 변경 REST 요청은 `X-CSRF-TOKEN`을 검증합니다.
- CSRF 발급은 `GET /api/v1/auth/csrf`이며, PortOne Webhook만 예외로 서명을 별도 검증합니다.

### 2.4. 호출량과 처리 자원 제한

| 대상 | 제한 |
|---|---|
| OAuth 시작 | 세션당 10분 5회·IP당 10분 30회 |
| Gemini 교정 | 사용자당 분당 5회·KST 일 30회·전체 동시 2건 |
| 사진 제출 | 사용자당 분당 5회·동일 배정 최대 5회 |
| EXIF 파싱 | 메타데이터 1 MiB·동시 4건·5초 제한 |

### 2.5. 멱등성과 요청 추적

- 구매·환불 요청·환불 승인·복구 명령은 `Idempotency-Key`(UUID)를 요구합니다.
- 같은 키·동일 요청은 최초 상태·응답 반환; 다른 Payload는 `IDEMPOTENCY_KEY_REUSED`, 처리 중이면 `IDEMPOTENCY_REQUEST_IN_PROGRESS`입니다.
- HTTP 응답과 서버 로그는 추적 ID로 연결합니다. 외부 PG 호출은 DB 잠금 바깥에서 실행합니다.

### 2.6. 시간 표현

응답 시각은 **UTC ISO 8601**로, DB는 UTC `DATETIME(6)`으로, 일일·주간·시즌 경계는 **`Asia/Seoul`**로 계산합니다.

### 2.7. 인증·온보딩 Endpoint

#### 구현할 기능

OAuth 시작·콜백·가입 확정·현재 사용자·로그아웃·초대 상태 조회를 제공합니다. 세부 Operation은 9.1절에 통합했습니다.

#### 주요 규칙

- `getCurrentUser`는 비회원·가입 대기·그룹 없음·일반 회원·전체 관리자 상태를 구분합니다.
- 초대 수락 시 신규 계정은 바로 만들지 않고, 가입 제출 직전에 링크·정원·제한을 재검사합니다.
- 초대 실패는 표준 오류·온보딩 사유로 전달하고 인증 상태는 안전하게 유지합니다.

## 3. PG 취소 결과 처리

### 구현할 기능

PG 취소의 **성공·실패·대기·결과 불명**을 별도의 내부 상태로 확정합니다.

### 주요 규칙

| 공급자 결과 | 내부 처리 |
|---|---|
| 성공 `SUCCEEDED` | 환불 `COMPLETED`, 구매 `CANCELLED`, 아이템 `REVOKED` |
| 명확한 실패 `FAILED` | 환불 `FAILED`, 소유권 유지 |
| 비동기 요청 `REQUESTED` | `CANCEL_PENDING`, 최신 상태 재조회 |
| Timeout·단절·불명 | `CANCEL_UNKNOWN`, 즉시 재취소 금지 |

### 완료 확인

결과 불명 상태에서 회수·이중 환불이 발생하지 않습니다.

## 4. 퀘스트 완료 판정 원칙

### 구현할 기능

서버의 배정·활동·사진 증빙을 기준으로 완료와 보상을 확정합니다.

### 주요 규칙

- `SELF_CHECK`·체크인은 클라이언트 보상값을 받지 않습니다.
- `SERVER_EVENT`는 서버가 확인한 업무 이벤트로, `AGGREGATE`는 같은 기간·멤버십·그룹 시즌의 기록으로 판단합니다.
- `PHOTO_EXIF`는 별도 Multipart 제출이며 GPS·촬영 시각·기간·성공 Commit을 모두 확인합니다.

### 완료 확인

위변조 입력·기간 종료·중복 완료가 보상에 반영되지 않습니다.

## 5. 크리처 홈 아바타 Presence 원칙

### 구현할 기능

활성 그룹원의 접속·퇴장·장착 상태와 크리처 성장 결과를 그룹 홈에 반영합니다.

### 5.1. WebSocket 목적지와 이벤트

#### 주요 규칙

- 그룹 구독은 `/topic/groups/{groupId}/live`; 인증된 현재 그룹만 구독할 수 있습니다.
- 서버가 발행한 확정 이벤트만 구독하며 클라이언트의 애플리케이션 `SEND`·`MESSAGE`는 거부합니다.
- Presence는 서버 메모리이며 접속 좌표·채팅 메시지 스트림을 제공하지 않습니다.

### 5.2. Snapshot과 재동기화

#### 처리 순서

1. `getCreatureHome`으로 현재 Snapshot을 조회합니다.
2. 인증된 소켓으로 현재 그룹의 라이브 이벤트를 구독합니다.
3. 재연결·이벤트 누락 의심 시 Snapshot을 재조회합니다.

#### 완료 확인

연결 끊김·중복 이벤트·다른 그룹 구독에도 화면이 최종 상태를 복원합니다.

## 6. 그룹·시즌·퀘스트·랭킹 Endpoint

각 기능의 Method·Path·요청 ID·DTO는 **9.2절 단일 Operation 목록**을 기준으로 합니다. 이 절은 특수 처리 규칙만 기술합니다.

### 6.1. 그룹과 멤버십

#### 주요 규칙

- 생성·가입·탈퇴·역할 변경·강퇴·주 관리자 이전은 서버에서 권한과 정원을 재검사합니다.
- 목록 `size` 기본 20·최대 50; 그룹 가입 시 **20명** 정원을 DB 트랜잭션으로 제어합니다.
- 강퇴는 저장 활동 시각에서 **72시간 10분** 경과 후에만 허용합니다.

### 6.1.1. Gemini 그룹 소개 교정

#### 구현할 기능

`POST /api/v1/ai/proofreads`가 그룹 소개 교정 제안을 반환합니다.

#### 주요 규칙

원문 **1~500자**이며 응답을 자동 저장하지 않습니다. 외부 서비스 실패 시 그룹 생성 자체는 가능합니다.

### 6.2. 시즌·크리처

#### 주요 규칙

- 시작 종은 그룹 참여 후 72시간 안에 주·부 관리자만 선택 가능합니다.
- 알 기간 EXP도 누적·부화 시 반영하며 시즌 종료·경계 처리 결과는 서버가 확정합니다.

### 6.3. 퀘스트와 활동

#### 주요 규칙

- 퀘스트 완료·체크인 Request Body는 없으며 서버가 배정·기간·세션에서 보상을 결정합니다.
- 사진은 `multipart/form-data`의 `assignmentId`·`file`만 받습니다.
- 완료 재요청은 기존 확정 결과를 반환하고, `QUEST_PERIOD_ENDED` 등은 표준 오류로 처리합니다.

### 6.4. 랭킹

#### 주요 규칙

- 개인·그룹 시즌 랭킹을 지원하며 동점 정렬은 **점수 도달 시각 → ID 오름차순**입니다.
- 종료 시즌은 Snapshot, 현재 시즌은 현재 확정 점수를 사용합니다.

## 7. 포트원 V2 결제·아바타·환불 API

### 7.1. 공급자 확장 경계

#### 주요 규칙

- 애플리케이션 서비스는 공급자 중립 `PaymentGateway` Port를 사용하며 PortOne SDK/REST/Webhook은 Adapter에서 변환합니다.
- 결제 조회 표준 결과는 `PAID`, `READY`, `CANCELLED`, `DEFINITIVE_FAILURE`, `UNKNOWN`입니다.
- 취소 표준 결과는 `SUCCEEDED`, `REQUESTED`, `DEFINITIVE_FAILURE`, `UNKNOWN`입니다.

### 7.2. 상점과 아바타

#### 구현할 기능

판매 상품·보유 아이템 조회, 슬롯별 장착·해제.

#### 주요 규칙

- 장착 슬롯은 `HEAD`, `FACE`, `BODY`, `ACCESSORY`입니다.
- 최초 장착 시 `confirmFirstUse=true`가 필요하며 누락 시 `FIRST_USE_CONFIRMATION_REQUIRED`입니다.
- 장착·해제의 Commit 이후 `AVATAR_EQUIPMENT_CHANGED`를 전송합니다.

### 7.3. 주문과 결제 검증

#### 처리 순서

1. `createPurchase`가 내부 주문번호·금액과 PortOne 결제 설정을 반환합니다.
2. 브라우저가 카카오페이 테스트 결제창을 호출합니다(PC `IFRAME`, 모바일 `REDIRECTION`).
3. `verifyPurchasePayment` 또는 Webhook이 서버에서 PG 최신 상태·Store ID·금액·통화를 확인합니다.
4. 같은 정합성 서비스로 주문 `PAID`·보유 아이템을 한 번만 확정합니다.

#### 완료 확인

브라우저 Redirect Query만으로 결제가 확정되지 않고 Webhook·검증 요청 경합에서도 한 번만 지급됩니다.

### 7.4. 환불과 포트원 취소

#### 처리 순서

1. 사용자가 미사용 구매의 환불을 요청합니다.
2. 관리자가 재검증해 `CANCEL_PENDING`을 선점하고 트랜잭션 밖에서 PG를 호출합니다.
3. 최신 PG 취소 결과로 `COMPLETED`·`FAILED`·`CANCEL_UNKNOWN`을 확정합니다.
4. `CANCEL_UNKNOWN`은 취소 거래 재조회 후 재처리합니다.

#### 완료 확인

사용 아이템 환불 차단, 중복 승인 차단, 결과 불명 상태의 장착·중복 취소 차단을 확인합니다.

### 7.5. 신고와 전체 관리자

#### 구현할 기능

신고 접수·기각·강퇴, 주 관리자 재지정, 운영 대시보드·경고를 조회합니다.

#### 주요 규칙

- 서비스 전체 명령은 `SUPER_ADMIN`만 사용합니다.
- 신고는 접수만으로 자동 강퇴하지 않으며, 처리 시 현재 멤버십을 재검사합니다.

### 7.6. 공식 연동 기준

PortOne V2·카카오페이 테스트, Google OpenID Connect, Naver OAuth 및 현재 프로젝트에서 확정한 Webhook 서명·이벤트 형식을 기준으로 합니다. 특정 공급자 오류는 내부 `PaymentGateway` Adapter에서 변환합니다.

## 8. 명령 DTO·응답 DTO·업무 오류 카탈로그

### 8.1. 주요 명령 DTO

| DTO | 주요 입력·제약 |
|---|---|
| `CreateGroupRequest` | `name`(2~30자), `description`(0~500자) |
| `ProofreadTextRequest` | `text`(1~500자) |
| `ChangeMemberRoleRequest` | `role` = `MEMBER` / `DEPUTY` |
| `RemoveMemberRequest` | `reason`(1~500자) |
| `TransferPrimaryRequest` | `targetUserId`, 관리자 재지정 시 `reason` 필수 |
| `SelectCreatureRequest` | `creatureSpeciesId` |
| `CreatePurchaseRequest` | `shopItemId` |
| `VerifyPaymentRequest` | `paymentId`(내부 주문번호 일치) |
| `EquipAvatarItemRequest` | `ownedItemId`, `confirmFirstUse` |
| `CreateRefundRequest` | `reason`(1~500자) |
| `ApproveRefundRequest` | `decisionReason`(1~500자) |
| `RejectRefundRequest` | `decisionReason`(1~500자) |

퀘스트 완료·체크인은 Body 없이 처리합니다. 모든 입력은 **서버에서** 다시 정규화·검증합니다.

### 8.2. 주요 응답 DTO

| DTO | 구현할 정보 |
|---|---|
| `CurrentUserResponse` | 인증·온보딩 상태, 그룹, 역할, 가입 제한 |
| `GroupSummaryResponse` | 그룹명·인원·정원·가입 가능 여부 |
| `CreatureHomeResponse` | 시즌·성장·기여·Presence Snapshot |
| `QuestAssignmentResponse` | 퀘스트 기간·조건·보상 Snapshot·완료 상태 |
| `AvatarResponse` | 슬롯별 장착·최초 사용 상태 |
| `PurchaseCheckoutResponse` | 주문번호·공개 결제 설정·확정 금액 |
| `PurchaseTimelineResponse` | 구매·거래·보유·환불 상태 이력 |
| `AdminDashboardResponse` | 지표·차트·최근 운영 처리 목록 |
| `ProofreadTextResponse` | 사용자가 선택할 교정 제안 |

### 8.3. 업무 오류 코드

| HTTP | 대표 오류 코드 | 처리 기준 |
|---|---|---|
| 400 | `INVALID_REQUEST`, `INVALID_FIELD_VALUE`, `SLOT_MISMATCH` | 입력 오류 |
| 401 | `AUTH_REQUIRED`, `SESSION_EXPIRED` | 인증 재진입 |
| 403 | `CSRF_INVALID`, `ROLE_FORBIDDEN` | 권한 거부 |
| 404 | `RESOURCE_NOT_FOUND` | 비공개·미존재 자원 |
| 409 | `SIGNUP_CONTEXT_EXPIRED`, `NICKNAME_ALREADY_USED`, `GROUP_NAME_ALREADY_USED` | 가입·이름 충돌 |
| 409 | `GROUP_FULL`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE`, `INVITE_INVALID` | 그룹 가입 실패 |
| 409 | `INACTIVITY_REQUIREMENT_NOT_MET`, `PRIMARY_TRANSFER_REQUIRED` | 관리 명령 불가 |
| 409 | `CREATURE_SELECTION_CLOSED`, `QUEST_PERIOD_ENDED`, `QUEST_COMPLETION_NOT_ALLOWED` | 시즌·퀘스트 경계 |
| 409 | `PHOTO_ATTEMPT_LIMIT_EXCEEDED`, `FIRST_USE_CONFIRMATION_REQUIRED` | 사진 횟수·최초 사용 확인 |
| 409 | `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_PENDING`, `PAYMENT_RESULT_UNKNOWN` | 결제 확정 불가 |
| 409 | `REFUND_NOT_ALLOWED_USED_ITEM`, `REFUND_ALREADY_ACTIVE`, `PG_CANCEL_RESULT_UNKNOWN` | 환불 제한·불명 |
| 409 | `CONCURRENT_MODIFICATION`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 동시성·멱등 충돌 |
| 413 | `FILE_TOO_LARGE` | 파일 크기 초과 |
| 429 | `RATE_LIMIT_EXCEEDED` | 호출량 제한 |

## 9. Endpoint별 요청·응답 계약

### 구현할 기능

모든 구현은 아래 **Operation ID = Controller 작업 식별자**를 사용합니다. 한 행이 한 API의 입력·성공 `data` 계약입니다.

### 주요 규칙

- JSON 요청은 `Content-Type: application/json`, Multipart는 해당 엔드포인트의 파일 필드를 사용합니다.
- 표에서 `-`는 별도 Body가 없음을 뜻합니다. 입력·응답의 필드명은 임의로 추가하지 않습니다.

### 9.1. 인증·초대

| Operation ID | 요청 ID | Method·경로 | 입력 | 성공 `data` |
|---|---|---|---|---|
| `getCsrfToken` | `REQ-AUTH-004` | `GET /api/v1/auth/csrf` | - | `CsrfResponse` |
| `createOAuthAttempt` | `REQ-AUTH-001`, `REQ-INV-001` | `POST /api/v1/auth/oauth-attempts` | Body `OAuthAttemptRequest` | `OAuthAttemptResponse` |
| `handleOAuthCallback` | `REQ-AUTH-001` | `GET /oauth2/callback/{provider}` | Path `provider`, Query `code`, `state`, `error` | CSR 경로로 `302` Redirect |
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
| `receivePortOneWebhook` | `REQ-PAY-001`, `REQ-PAY-002` | `POST /api/v1/payments/webhooks/portone` | PortOne 서명 Header·V2 Webhook | `202 Accepted`, `EmptyResponse` |
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
| `200 OK` | 위 Operation을 제외한 모든 Operation (`DELETE` 포함, `204` 미사용) |

멱등 재요청은 최초 응답의 HTTP 상태와 Body를 그대로 반환합니다.

#### 모든 Operation에 적용하는 공통 오류

| 적용 범위 | 오류 |
|---|---|
| 인증 필요 | `AUTH_REQUIRED`, `SESSION_EXPIRED` |
| 상태 변경 | `CSRF_INVALID` |
| 권한·자원 | `ROLE_FORBIDDEN`, `RESOURCE_NOT_FOUND` |
| 입력 | `INVALID_REQUEST`, `INVALID_FIELD_VALUE` |
| 상태·경합 | `CONCURRENT_MODIFICATION` 및 해당 명령의 도메인 오류 |

**업무별 주요 오류**는 8.3절을 사용합니다. 구현 테스트에서는 성공·인증·권한·잘못된 입력·중복 요청·도메인 경계 상황을 확인합니다.

## 10. 요청 필드 상세 정의

### 구현할 기능

모든 Path·Query·Header·Body를 서버 DTO에서 검증합니다.

### 10.1. 공통 Path·Query·Header

| 필드 | 제약 |
|---|---|
| `*Id` | 양의 64-bit 정수 |
| 초대 `token` | URL-safe 32~128자 |
| OAuth `provider` | `google`, `naver` |
| OAuth `code` / `state` | 인가 Code 1~2048자, `state` 32~128자·서버 저장값 검증 |
| `page` / `size` | 기본 `0` / `20`, size 최대 `50` |
| `q` | NFKC·Trim 후 0~30자 |
| `seasonId` | 1 이상, 없으면 현재 시즌 |
| `periodType` | `DAILY` / `WEEKLY` |
| `slot` | `HEAD` / `FACE` / `BODY` / `ACCESSORY` |
| `range` | `7_DAYS` / `30_DAYS` / `CURRENT_SEASON` |
| `Idempotency-Key` | 해당 명령 필수 UUID |
| `X-CSRF-TOKEN` | 현재 세션의 상태 변경 요청 필수(Webhook 제외) |

알 수 없는 Query 키·중복 Query·쉼표 구분 입력은 무시하지 않고 `400 INVALID_FIELD_VALUE`로 처리합니다.

#### 10.1.1. Endpoint별 Query DTO

| DTO | 주요 선택 조건 |
|---|---|
| `PublicGroupsQuery` | `q`, `page`, `size` |
| `GroupMembersQuery` | `page`, `size` |
| `CurrentQuestsQuery` | `periodType` |
| `MemberRankingQuery` / `GroupRankingQuery` | `seasonId`, 페이지 범위 |
| `ShopItemsQuery`, `OwnedItemsQuery` | 페이지 및 해당 목록 필터 |
| `PurchasesQuery`, `RefundRequestsQuery` | 페이지 및 상태 필터 |
| `AdminReportsQuery`, `AdminDashboardQuery`, `OperationalWarningsQuery` | 관리자 집계·조회 필터 |

정렬 순서는 각 Operation 서버 정책으로 고정하며 임의 정렬 문자열을 받지 않습니다.

### 10.2. 인증·그룹 요청 Body

| DTO | 주요 필드 |
|---|---|
| `OAuthAttemptRequest` | 로그인 공급자와 초대 문맥 |
| `SignupRequest` | `nickname` 및 가입 대기 문맥의 확정 |
| `CreateGroupRequest` | `name`, `description` |
| `ChangeMemberRoleRequest` | `role` |
| `RemoveMemberRequest` | `reason` |
| `TransferPrimaryRequest` | `targetUserId`, 필요 시 `reason` |

초대 수락·거절, 그룹 가입·탈퇴, 링크 재발급은 클라이언트에서 그룹·사용자 계산값을 Body로 전달하지 않습니다.

### 10.3. 퀘스트·상품·결제·관리 요청 Body

| DTO | 주요 필드 |
|---|---|
| `ProofreadTextRequest` | `text` |
| `SelectCreatureRequest` | `creatureSpeciesId` |
| 사진 Multipart | `assignmentId`, `file` |
| `CreatePurchaseRequest` | `shopItemId` |
| `VerifyPaymentRequest` | `paymentId` |
| `EquipAvatarItemRequest` | `ownedItemId`, `confirmFirstUse` |
| `CreateRefundRequest` | `reason` |
| `ApproveRefundRequest` / `RejectRefundRequest` | `decisionReason` |
| 신고 관련 명령 | 신고 대상·사유 및 해당 관리자 결정 사유 DTO |

완료 시각·경험치·기여도·누적 횟수는 서버가 계산하며 Body로 받지 않습니다.

### 10.4. Webhook 입력

#### 주요 규칙

- PortOne V2 **Standard Webhooks Signature**를 우선 검증합니다.
- 서명 실패는 `400` 빈 Body이며, 검증된 중복·알 수 없는 Event·미등록 주문은 `202`로 처리하고 잘못된 지급을 실행하지 않습니다.
- 서명 통과 후 결제 식별자를 보존하고 PortOne 최신 결제를 조회하여 정합성 서비스로 처리합니다.
- 전체 Payload는 저장하지 않고 정합성 복구에 필요한 최소 거래 식별 정보만 남깁니다.

## 11. 응답 필드 상세 정의

### 주요 규칙

응답 DTO는 성공 `data` 안에 위치합니다. 빈 목록은 `[]`; `null 가능` 필드는 값을 `null`로 두되 키를 제거하지 않습니다. 조회와 명령이 참조하는 핵심 데이터는 9절 응답 DTO명을 기준으로 합니다.

### 11.1. 공통·인증·초대 응답

| DTO | 필수 의미 |
|---|---|
| `CsrfResponse` | `headerName`, `token` |
| `OAuthAttemptResponse` | 공급자 인증 URL·만료 시각 |
| `SignupContextResponse` | 공급자·이메일·제안 닉네임·초대·30분 만료 |
| `CurrentUserResponse` | 인증 상태·온보딩 상태·사용자·멤버십·역할·제한 종료 |
| `NavigationResponse` | 다음 이동 경로 |
| `PublicInvitationResponse` | 공개 초대 및 대상 그룹 |

### 11.2. 그룹 응답

| DTO | 필수 의미 |
|---|---|
| `GroupSummaryResponse`, `GroupDetailResponse` | 그룹 식별·이름·인원·정원·현재 그룹 상태 |
| `MembershipResponse`, `GroupMemberResponse` | 소속·역할·활동·종료 상태 |
| `InvitationManagementResponse`, `InvitationRotatedResponse` | 활성 초대 링크 및 재발급 결과 |
| `MembershipExitResponse` | 탈퇴·강퇴·재가입 제한 결과 |

### 11.3. 시즌·크리처·퀘스트·랭킹 응답

| DTO | 필수 의미 |
|---|---|
| `SeasonResponse`, `CreatureHomeResponse` | 시즌·크리처·경험치·Presence Snapshot |
| `CreatureSpeciesResponse`, `CreatureSelectionResponse` | 종·선택 및 마감 상태 |
| `QuestAssignmentResponse`, `QuestCompletionResponse` | 배정·완료·보상·상태 |
| `CheckInResponse`, `PhotoSubmissionResponse` | 체크인·사진 판정·실패 사유 |
| `MemberRankResponse`, `GroupRankResponse` | 시즌 점수·순위·대상 ID |

Rank 목록에는 `meta.currentSubject`를 항상 포함하고 현재 대상이 없으면 `null`로 반환합니다. 페이지 목록의 `meta`는 `page`, `size`, `totalElements`, `totalPages`입니다.

### 11.4. 상점·아바타·결제·환불 응답

| DTO | 필수 의미 |
|---|---|
| `ShopItemResponse`, `OwnedItemResponse` | 상품·보유·사용·가격·슬롯 |
| `AvatarResponse` | 슬롯별 장착·사용 여부 |
| `PurchaseCheckoutResponse` | 결제창 초기화 값·주문번호·금액 |
| `PurchaseSummaryResponse`, `PurchaseTimelineResponse` | 주문·PG 거래·현재 상태·시각 |
| `RefundResponse`, `RefundDetailResponse` | 환불 상태·사유·취소 결과·관리자 처리 |

### 11.5. 신고·관리자 응답

| DTO | 필수 의미 |
|---|---|
| `ReportResponse`, `ReportDetailResponse` | 신고·처리 상태·감사 정보 |
| `AdminDashboardResponse` | 사용자·그룹·퀘스트·결제·환불 등 서버 집계 및 차트 값 |
| `OperationalWarningResponse` | 현재 미해결 경고의 유형·원인·원본 상태 |

차트 입력은 서버 집계값입니다. 브라우저가 원본 행을 다시 계산하지 않습니다. 경고는 별도의 해결 이력·상태 필터를 제공하지 않습니다.

## 12. 공통 형식·Enum·입력 검증 결과

### 12.1. JSON 형식

| 형식 | 규칙 |
|---|---|
| 식별자 | 숫자 ID는 1 이상 정수 |
| 금액 | 원 단위 정수 |
| 일시 | UTC ISO 8601 문자열 |
| 배열 | 빈 값은 `[]`, `null`로 대체하지 않음 |
| Enum | 대문자 고정 문자열, 미등록 값은 검증 오류 |
| FieldErrors | 필드 경로와 사유를 배열로 반환 |

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

#### 주요 규칙

```json
{
  "code": "INVALID_FIELD_VALUE",
  "message": "입력값을 확인해 주세요.",
  "fieldErrors": [
    { "field": "name", "message": "허용 길이를 확인해 주세요." }
  ],
  "traceId": "요청 추적 ID",
  "timestamp": "2026-11-02T06:38:28Z"
}
```

필드 오류는 정규화한 입력 경로를 사용합니다. 인증·CSRF 실패에서는 Body 검증을 실행하지 않습니다.

## 13. 초보 구현 체크리스트와 예시

### 13.1. 백엔드 구현 순서

1. 9절의 Operation ID를 Controller 동작으로 연결합니다.
2. 10절 기준으로 Path·Query·Header·Body를 검증합니다.
3. 세션·권한·CSRF를 검사한 뒤 Service에서 업무 규칙을 처리합니다.
4. Repository·트랜잭션·UNIQUE·멱등성으로 확정합니다.
5. 11절 응답 DTO로 변환하고 9.5절의 HTTP 상태·오류를 반환합니다.
6. Commit 이후 이벤트를 발행하고 성공·실패·경합 테스트를 수행합니다.

### 13.2. JSON 명령 프론트엔드 예시

```javascript
/**
 * 그룹 생성을 요청하고 성공 응답의 data만 반환합니다.
 * JSON 요청에는 세션 쿠키와 CSRF를 포함합니다.
 * 실패 시에는 서버의 불변 code를 화면 로직으로 전달합니다.
 */
export async function createGroup(payload, csrfToken) {
  // 인증 세션을 전송하고 공통 REST 계약의 헤더를 사용합니다.
  const response = await fetch('/api/v1/groups', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken },
    body: JSON.stringify(payload),
  });

  // 실패 분기는 message가 아닌 code로 처리합니다.
  const body = await response.json();
  if (!response.ok) throw body;
  return body.data;
}
```

### 13.3. Multipart 사진 제출 예시

```javascript
/**
 * 사용자가 선택한 JPEG 파일과 배정 ID만 전송합니다.
 * 브라우저에서 boundary가 지정되도록 Content-Type은 설정하지 않습니다.
 * 서버가 EXIF·기간·소속을 검증한 결과만 신뢰합니다.
 */
export async function submitQuestPhoto(assignmentId, file, csrfToken) {
  // Multipart의 필드명은 API 계약과 정확히 일치해야 합니다.
  const form = new FormData();
  form.append('assignmentId', String(assignmentId));
  form.append('file', file);

  // 파일 MIME과 시그니처 판정은 서버에서도 다시 수행합니다.
  const response = await fetch('/api/v1/photo-submissions', {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-CSRF-TOKEN': csrfToken },
    body: form,
  });
  const body = await response.json();
  if (!response.ok) throw body;
  return body.data;
}
```

### 13.4. 프론트엔드 오류 표시 규칙

- 입력 오류는 `fieldErrors[].field`를 해당 폼 아래 오류에 연결합니다.
- 인증 만료는 로그인·재인증으로 안내하고 권한 오류는 요청한 기능을 차단합니다.
- `409`는 코드별 충돌·기한·정원·중복 사유를 명확하게 표시합니다.
- PG 불명확 결과는 성공·실패로 추정하지 않고 상태 확인 화면을 제공합니다.

