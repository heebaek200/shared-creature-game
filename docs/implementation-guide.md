# 초보 개발자를 위한 구현 시작 안내서

## 1. 이 문서의 목적

이 문서는 설계 문서를 처음 보는 개발자가 기능 하나를 백엔드와 프론트엔드에 옮기는 순서를 설명합니다. 새로운 기능이나 규칙을 정의하지 않으며, 상세 값은 반드시 연결된 원문 설계서를 기준으로 합니다.

문서가 서로 다르게 보이면 임의로 코딩하지 않습니다. [의사결정 기록](decision-log.md)에서 최신 결정을 확인하고, 그래도 판단할 수 없으면 설계 누락으로 팀에 질문합니다.

## 2. 기능 하나를 구현하는 고정 순서

예를 들어 `createGroup`을 구현한다면 아래 순서를 그대로 따릅니다.

1. [사용자 시나리오 및 업무 규칙](business-rules.md)에서 Operation에 연결된 요청 ID `REQ-GRP-001`의 사전 조건·성공 결과·실패 조건을 읽습니다.
2. [화면 설계서](screen-design.md)에서 `/groups/select`의 버튼, 입력 요소, 성공 후 이동 경로를 확인합니다.
3. [REST API 설계서](api-design.md)의 9절에서 `createGroup`의 Method·Path·입력 위치·응답 DTO를 찾습니다.
4. 같은 문서 9.5절에서 성공 HTTP 상태와 오류 코드를, 10절에서 요청 필드 제한을, 11절에서 응답 필드를 확인합니다.
5. [데이터베이스 설계서](database-design.md)에서 관련 테이블·컬럼·UNIQUE·FK·동시성 규칙을 확인합니다.
6. [클래스 다이어그램](class-diagram.md)에서 담당 Controller·Service·Repository·외부 Port를 찾습니다.
7. [코드 컨벤션](convention.md)에 맞춰 백엔드를 구현하고 Controller 테스트를 작성합니다.
8. 아래 화면별 표에서 사용할 Operation을 확인해 프론트엔드 `api` 함수와 Page를 구현합니다.
9. 응답 DTO와 같은 모양의 목 데이터를 만들고 정상·빈 값·오류 화면을 확인합니다.
10. 목 모드를 끄고 실제 백엔드와 연결한 뒤 완료 체크리스트를 확인합니다.

## 3. 설계에서 코드로 옮기는 규칙

### 3.1. 백엔드

Operation 하나는 다음 흐름으로 구현합니다.

```text
HTTP 요청
  → Controller: Path·Query·Header·Body를 DTO로 변환하고 형식 검증
  → Service: 로그인 사용자, 권한, 상태와 업무 규칙 검사
  → Repository/외부 Port: DB 또는 외부 시스템 작업
  → Service: Response DTO 생성
  → Controller: 성공 HTTP 상태와 공통 Envelope 반환
```

- Controller 메서드와 테스트 이름에는 API 설계서의 Operation ID를 사용합니다.
- Request·Response DTO의 JSON 필드명과 타입은 API 설계서 표를 그대로 사용합니다. Entity를 요청이나 응답에 직접 사용하지 않습니다.
- Service는 업무 규칙 문서의 실패 조건을 API 설계서의 오류 코드로 변환합니다.
- DB 상태를 바꾸는 작업은 Service의 트랜잭션 안에서 수행합니다. 잠금과 재시도는 데이터베이스 설계서의 동시성 표를 따릅니다.
- PortOne, OAuth, Gemini를 직접 Controller에서 호출하지 않고 클래스 다이어그램의 Port를 거칩니다.
- 문서에 없는 상태, Enum, Request 필드나 Endpoint가 필요해 보이면 먼저 설계를 보완합니다.

권장 파일 찾기 규칙은 아래와 같습니다. 실제 폴더는 스피어 프로토콜에서 생성된 구조를 우선합니다.

| 찾을 대상 | 기준 |
|---|---|
| Controller | Operation이 속한 기능 Package의 `*Controller` |
| Request·Response DTO | 같은 기능의 `api` 영역. JSON 필드명은 API 설계서와 동일 |
| Service | 클래스 다이어그램에서 Operation을 소유한 `*Service` |
| Repository·Entity | 데이터베이스 설계서의 관련 테이블과 클래스 다이어그램 관계 |
| 외부 연동 | 클래스 다이어그램의 Port Interface와 Adapter |
| 테스트 | Operation ID가 드러나는 Controller 또는 Service 테스트 |

### 3.2. 프론트엔드

화면 하나는 다음 흐름으로 구현합니다.

```text
Router
  → Page mount
  → api 함수 호출
  → response.data를 DOM에 표시
  → 사용자 Event에서 명령 api 호출
  → 성공 시 Snapshot 재조회 또는 설계된 경로로 이동
  → Page cleanup
```

- Page는 URL을 직접 조합해 `fetch`하지 않고 Operation ID와 같은 이름의 `api` 함수를 호출합니다.
- `api` 모듈만 Cookie, CSRF, 공통 Envelope와 `ApiError`를 처리합니다.
- 화면 문구가 아니라 오류 `code`로 분기합니다. `message`는 표시용입니다.
- 서버에서 받은 문자열은 `innerHTML`이 아니라 `textContent`로 표시합니다.
- 입력 요소의 `required`, `minlength`, `maxlength`, `accept`는 API 설계서 10절과 같게 설정합니다.
- 목록의 정렬은 서버가 고정하므로 프론트엔드가 임의로 다시 정렬하지 않습니다.
- 화면을 떠날 때 Event, Timer, WebSocket Subscription과 Bootstrap Instance를 정리합니다.

### 3.3. 처음 한 번 따라 할 예: `createGroup`

아래 순서는 코드를 복사하라는 뜻이 아니라 각 설계 값을 어느 파일에 옮기는지 보여주는 예입니다.

#### 백엔드

1. API 설계서에서 `POST /api/v1/groups`, Body `CreateGroupRequest`, 응답 `CreateGroupResponse`, 성공 `201`을 찾습니다.
2. `CreateGroupRequest`에 `name`, `description`만 선언하고 10.2절의 길이·정규화 규칙을 검증합니다.
3. `GroupController.createGroup()`은 로그인 사용자 ID와 Request DTO를 `GroupMembershipService.createGroup()`에 전달합니다.
4. Service는 `REQ-GRP-001`에 따라 활성 그룹 소속, 24시간 제한과 이름 중복을 검사합니다.
5. 데이터베이스 설계서의 잠금 순서대로 사용자·그룹 관련 상태를 확인하고 `groups`, `group_memberships`, `group_invite_links`를 한 트랜잭션에서 생성합니다.
6. Service는 생성된 Entity를 그대로 반환하지 않고 `CreateGroupResponse`를 만듭니다.
7. Controller는 `201`과 Success Envelope를 반환합니다. 이름 중복은 `GROUP_NAME_ALREADY_USED`, 기존 그룹은 `ALREADY_IN_GROUP`, 제한 중이면 `JOIN_COOLDOWN_ACTIVE`입니다.
8. 테스트는 정상 생성, 이름 길이 위반, 이름 중복, 기존 소속과 24시간 제한을 각각 확인합니다.

Controller 모양은 다음 정도로 단순하게 유지합니다. 공통 `ApiResponse` 이름은 스피어에서 생성된 실제 공통 클래스를 사용합니다.

```java
/** 새 공개 그룹을 만들고 요청자를 주 관리자로 가입시킵니다. */
@PostMapping("/api/v1/groups")
public ResponseEntity<ApiResponse<CreateGroupResponse>> createGroup(
        @AuthenticationPrincipal LoginUser loginUser,
        @RequestBody CreateGroupRequest request) {
    CreateGroupResponse result = groupMembershipService.createGroup(loginUser.id(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
}
```

형식 검증을 Bean Validation으로 할지 `request.validate()`로 할지는 팀이 선택할 수 있습니다. 어느 방식을 사용해도 API 설계서의 제한과 Error Envelope는 같아야 합니다.

#### 프론트엔드

1. `/groups/select` Page의 그룹 생성 Form에 `name`, `description`만 둡니다. `name`은 `required`, `minlength=2`, `maxlength=30`, `description`은 `maxlength=500`으로 설정합니다.
2. `api` 영역에 `createGroup(input)` 함수를 만들고 API 설계서 13.2절 예시처럼 JSON, Cookie와 CSRF를 보냅니다.
3. Submit Event에서 중복 클릭을 막고 `createGroup()`을 한 번 호출합니다.
4. 성공하면 `/creature`로 이동합니다. `fieldErrors`는 입력 아래에, `GROUP_NAME_ALREADY_USED`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE`는 Form 위의 공통 안내 영역에 표시합니다.
5. Cleanup에서 Submit Event를 제거합니다.
6. 정상 응답, 세 업무 오류와 이름 길이 오류 목 데이터를 확인한 뒤 실제 백엔드와 연결합니다.

## 4. 화면별 REST API 연결표

공통 부트스트랩은 모든 보호 화면에서 `getCurrentUser`, 상태 변경 전에 `getCsrfToken`, 로그아웃에서 `logout`을 사용합니다. 아래 표에 없는 Operation은 브라우저 화면이 아니라 OAuth 공급자, PortOne Webhook 또는 관리자 복구 작업에서 사용됩니다.

| 화면 경로 | 처음 조회할 Operation | 사용자 동작 Operation | 성공 후 처리 |
|---|---|---|---|
| `/login` | `getCurrentUser` | `createOAuthAttempt` | 반환된 `authorizationUrl`로 이동 |
| `/invite/:token` | `getPublicInvitation` | `acceptInvitation`, `rejectInvitation` | `NavigationResponse.nextRoute`로 이동 |
| `/signup` | `getSignupContext` | `completeSignup` | `CurrentUserResponse` 상태로 Route Guard 실행 |
| `/groups/select` | `listPublicGroups` | `proofreadGroupDescription`, `createGroup`, `joinGroup` | 성공 시 `/creature`, 업무 오류는 현재 화면 표시 |
| `/creature` | `getCurrentSeason`, `getCreatureHome` | 없음 | WebSocket Event 뒤 `getCreatureHome` 재조회 |
| `/quests` | `listCurrentQuests` | `completeQuestAssignment`, `createCheckIn`, `submitQuestPhoto` | 성공 후 해당 기간 퀘스트 재조회 |
| `/rankings` | `getMemberRanking`, `getGroupRanking` | Page·시즌 Query 변경 | 선택한 Ranking 재조회 |
| `/group` | `getMyGroup`, `listMyGroupMembers` | `leaveMyGroup`, `createUserReport` | 탈퇴 성공 시 `/groups/select`; `groupDeactivated=true`면 그룹도 함께 종료됐음을 안내. 신고 성공 시 안내 |
| `/shop` | `listShopItems`, 필요 시 `getShopItem` | `createPurchase`, `verifyPurchasePayment` | 검증 성공 후 구매 상세 또는 아바타로 이동 |
| `/avatar` | `getAvatar`, `listOwnedItems` | `equipAvatarItem`, `unequipAvatarItem` | 반환된 `AvatarResponse`로 다시 그림 |
| `/payments` | `listPurchases`, `listMyRefundRequests` | `getPurchase`, `createRefundRequest` | 해당 구매·환불 상태 재조회 |
| `/group-admin` | `getMyGroup`, `listMyGroupMembers`, `getInvitationManagement`, `listActiveCreatureSpecies` | `changeMemberRole`, `removeGroupMember`, `transferPrimaryAdmin`, `rotateInvitation`, `selectCreatureSpecies` | 변경 후 관련 Snapshot 재조회 |
| `/admin` | `getAdminDashboard` | 기간·그룹 Query 변경 | 전체 Dashboard 응답으로 카드·표·차트 갱신 |
| `/admin/reports` | `listAdminReports` | `getAdminReport`, `dismissReport`, `removeReportedMember` | 목록과 선택 상세 재조회 |
| `/admin/refunds` | `listAdminRefundRequests` | `getAdminRefundRequest`, `approveRefund`, `rejectRefund`, `reconcileRefund` | 목록과 선택 상세 재조회 |
| `/admin/operations` | `listOperationalWarnings` | `transferPrimaryAdminAsSuperAdmin` | 경고 목록과 대상 그룹 재조회 |

브라우저가 직접 호출하지 않는 Operation은 다음과 같습니다.

| Operation | 호출 주체·목적 |
|---|---|
| `handleOAuthCallback` | Google·Naver가 백엔드 Callback으로 Redirect |
| `receivePortOneWebhook` | PortOne 서버가 결제·환불 상태 통지 |

## 5. 기능별 백엔드 시작점

| 기능 | Operation 범위 | 주요 Service·Port | 먼저 볼 테이블 |
|---|---|---|---|
| 인증·가입 | `getCsrfToken` ~ `logout` | `AuthService`, `SocialLoginClient` | `users`, `social_accounts`, `user_profiles`, `oauth_login_attempts`, Spring Session 테이블 |
| 초대·그룹 | `getPublicInvitation` ~ `rotateInvitation` | `GroupMembershipService`, `GroupAdminService` | `groups`, `group_memberships`, `group_invite_links`, `group_join_restrictions` |
| 시즌·크리처 | `getCurrentSeason` ~ `selectCreatureSpecies` | 시즌·크리처 Service | `seasons`, `creature_species`, `group_seasons`, `group_creatures` |
| 퀘스트 | `listCurrentQuests` ~ `submitQuestPhoto` | 퀘스트 완료·보상 Service | `quest_templates`, `quest_assignments`, `quest_completions`, `quest_reward_ledgers`, `photo_submissions` |
| 랭킹 | `getMemberRanking`, `getGroupRanking` | Ranking Query Service | `season_member_contributions`, `season_group_scores`, 시즌 종료 Ranking Snapshot 테이블 |
| 아바타·상점 | `listShopItems` ~ `unequipAvatarItem` | Avatar Service | `shop_items`, `owned_items`, `avatar_equipments` |
| 결제·환불 | `createPurchase` ~ `reconcileRefund` | Payment·Refund Service, `PaymentGateway` | `purchases`, 결제 이력, `refund_requests`, `api_idempotency_records` |
| 신고·전체 관리자 | `createUserReport` ~ `listOperationalWarnings` | Admin Service | `user_reports`, 감사·Scheduler 및 각 원본 상태 테이블 |

정확한 컬럼명과 전체 40개 테이블은 데이터베이스 설계서가 기준입니다. 위 표는 시작 위치를 찾기 위한 요약이며 테이블 정의를 대체하지 않습니다.

## 6. 목 데이터로 프론트엔드를 먼저 만드는 방법

1. API 설계서 11절의 Response DTO 필드를 그대로 복사해 정상 응답 객체를 만듭니다.
2. Page 목록이면 `data: []`인 빈 응답도 만듭니다.
3. API 설계서 9.5절 오류 중 화면이 처리할 오류 하나 이상을 Error Envelope로 만듭니다.
4. `mock-api.js`가 실제 `api` 함수와 같은 `data`를 반환하거나 같은 `ApiError`를 던지게 합니다.
5. Page는 목 모드인지 몰라야 합니다. `VITE_USE_MOCK_API` 분기는 공통 `api` 경계 한 곳에만 둡니다.

```javascript
// 실제 API의 Success Envelope와 같은 모양입니다.
export const emptyPublicGroupsResponse = {
  data: [],
  meta: {
    serverTime: '2026-11-02T00:00:00Z',
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  },
};

export const groupFullError = {
  code: 'GROUP_FULL',
  message: '그룹 정원이 모두 찼습니다.',
  fieldErrors: [],
  traceId: 'mock-group-full',
  timestamp: '2026-11-02T00:00:00Z',
};
```

## 7. Operation 완료 체크리스트

### 백엔드

- [ ] 요청 ID의 사전 조건·성공·실패 규칙을 Service에 반영했습니다.
- [ ] Method·Path·입력 위치와 JSON 필드명이 API 설계서와 같습니다.
- [ ] 길이·범위·Enum·파일 제한을 검증합니다.
- [ ] 권한을 화면이 아니라 서버에서 다시 검사합니다.
- [ ] 성공 HTTP 상태, Envelope와 응답 DTO가 계약과 같습니다.
- [ ] 공통 오류와 Operation별 업무 오류 코드가 계약과 같습니다.
- [ ] DB UNIQUE·FK·잠금·멱등성 규칙을 지킵니다.
- [ ] 정상, 입력 오류, 권한 오류, 대표 업무 오류 테스트가 있습니다.
- [ ] ID로 자원을 조회할 때 현재 사용자 소유권을 Service에서 검사하고, 다른 사용자의 구매·아이템·환불은 `404`로 숨깁니다.
- [ ] OAuth Callback 세션 귀속, STOMP `CONNECT`·`SUBSCRIBE`·`UNSUBSCRIBE`·`DISCONNECT` 허용 목록과 적용되는 호출량·동시 처리 제한을 테스트했습니다.
- [ ] Webhook은 서명 검증 직후 `provider_payment_id`를 저장하고 `purchase_id=null`인 실패도 최신 결제 조회로 복구할 수 있습니다.

### 프론트엔드

- [ ] 화면 설계서의 접근 등급별 Route Guard, 입력, 버튼, 빈 상태와 성공 이동을 구현했습니다. 그룹 없음만으로 로그인 전용 화면이나 전체 관리자 화면을 차단하지 않습니다.
- [ ] Operation ID와 같은 `api` 함수를 통해서만 서버를 호출합니다.
- [ ] `credentials`, CSRF, JSON 또는 Multipart 형식을 지킵니다.
- [ ] STOMP 연결 시 메모리에 있는 `X-CSRF-TOKEN`을 CONNECT Native Header로 전달하고 화면 정리 때 현재 구독을 `UNSUBSCRIBE`하며 애플리케이션 메시지를 서버로 보내지 않습니다.
- [ ] `fieldErrors`, `401`, `403`, `409`, 외부 장애와 알 수 없는 오류를 처리합니다.
- [ ] 정상·빈 목록·대표 오류 목 데이터로 화면을 확인했습니다.
- [ ] 모바일 크기, 라이트·다크 모드와 키보드 Focus를 확인했습니다.
- [ ] 목 모드를 끄고 실제 백엔드와 한 번 이상 연결했습니다.

### 문서 정합성

- [ ] 구현 때문에 계약이 바뀌었다면 관련 요청 ID, 화면, API, DB와 의사결정 기록을 함께 수정했습니다.
- [ ] 문서에 없는 기능을 조용히 추가하거나 기존 필드를 다른 뜻으로 사용하지 않았습니다.

## 8. HTTPS를 직접 구축하지 않는 통합 검증

평상시에는 `local` Profile과 `http://localhost:5173`만 사용합니다. 이때만 세션 쿠키의 `Secure=false`를 허용합니다. 모바일 OAuth, PortOne Redirect·Webhook과 실기기 사진 제출을 함께 확인할 때에는 다음 순서를 사용합니다.

1. Spring Boot를 localhost에서 실행합니다.
2. Vite를 실행하고 `/api`, `/oauth2`, `/ws` Proxy가 Spring Boot를 가리키는지 확인합니다.
3. 팀에서 선택한 임시 HTTPS 터널로 Vite Port 하나만 공개합니다. 특정 터널 제품은 프로젝트 설계에 고정하지 않습니다.
4. 발급된 HTTPS Origin을 `integration` Profile의 `APP_BASE_URL`에 넣고 세션 쿠키 `Secure=true`를 사용합니다.
5. 같은 Origin의 OAuth Callback, 결제 Redirect와 `/api/v1/payments/webhooks/portone`을 Google·Naver·PortOne 테스트 설정에 등록합니다.
6. PC와 모바일에서 로그인, 결제 복귀, Webhook 수신, WebSocket과 사진 제출을 확인합니다.

터널 URL이 바뀌면 공급자 콘솔의 테스트 URL도 함께 바꿉니다. HTTPS 인증서 발급, 도메인 구매와 운영 서버 구축은 이번 프로젝트 범위가 아닙니다.
