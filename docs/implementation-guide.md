# 초보 개발자를 위한 구현 시작 안내서

## 1. 이 문서의 목적

### 구현할 기능

요구사항 한 건을 담당 Controller·Service·Repository·프론트 Page·테스트로 옮기기 위한 **작업 순서표**를 제공합니다.

### 주요 규칙

- 업무 허용 조건: [비즈니스 룰](business-rules.md)
- 화면 경로·표시: [화면 설계](screen-design.md)
- Method·Path·DTO·오류: [API 설계](api-design.md)
- 테이블·FK·잠금: [DB 설계](database-design.md)
- 코드 담당 범위: [클래스 다이어그램](class-diagram.md), [컨벤션](convention.md)

## 2. 기능 하나를 구현하는 고정 순서

`createGroup`(`REQ-GRP-001`)을 예로 든 기본 작업 순서입니다.

| 순서 | 확인·구현 대상 | 완료 확인 |
|---:|---|---|
| 1 | [업무 규칙](business-rules.md): 요청 ID와 성공·실패 조건 | 예외 조건을 설명할 수 있음 |
| 2 | [화면 설계](screen-design.md): `/groups/select`의 입력과 이동 | 구현 화면·버튼 확정 |
| 3 | [API 설계 9절](api-design.md#9-endpoint별-요청응답-계약): Method·Path·Operation | `createGroup` 확인 |
| 4 | [API 9.5·10·11절](api-design.md#95-operation별-성공-상태와-오류): 성공·오류·DTO 계약 | 계약을 테스트로 정의 |
| 5 | [DB 설계](database-design.md): 관련 테이블·UNIQUE·FK·잠금 | 저장·경합 조건 확인 |
| 6 | [클래스 다이어그램](class-diagram.md): 계층별 담당 | 변경 파일 결정 |
| 7 | 백엔드 Controller·Service·Repository·테스트 | 실제 API 정상·오류 검증 |
| 8 | `features/` API 함수와 `pages/` DOM·Event | 화면 연결 |
| 9 | Mock 정상·빈 값·오류 시나리오 | 서버 없이 검증 |
| 10 | Mock 해제 후 실제 통합 확인 | PR 준비 |

## 3. 설계에서 코드로 옮기는 규칙

### 3.1. 백엔드

#### 구현할 기능

`HTTP → Controller(형식) → Service(권한·업무) → Repository/Port(DB·외부 연동) → DTO → Envelope` 흐름을 유지합니다.

#### 주요 규칙

- Controller Operation과 테스트는 **API 설계서의 Operation ID**를 기준으로 합니다.
- Entity를 Request·Response로 직접 노출하지 않고, Service에서 업무 오류를 API 오류 코드로 변환합니다.
- DB 상태 변경과 잠금은 Service 트랜잭션에서 처리합니다. PG·OAuth·Gemini는 Controller가 직접 호출하지 않고 Port를 사용합니다.
- 문서에 없는 Enum·Endpoint·상태를 편의상 추가하지 않습니다.

#### 작업 대상

| 담당 | 찾는 위치·판단 기준 |
|---|---|
| Controller | 기능별 `*Controller`, Path·Query·Header·Body 유효성 검사 |
| DTO | 기능별 `api` 영역, 원본 JSON 필드명·타입 유지 |
| Service | 클래스 다이어그램에서 Operation을 소유한 `*Service` |
| Entity·Repository | DB 설계서의 책임 테이블과 FK·UNIQUE 확인 |
| 외부 연동 | Port Interface·Adapter |
| 테스트 | Operation ID 기반 Controller·Service 테스트 |

#### 완료 확인

성공 응답의 HTTP 상태·DTO, 권한 거부, 필드 오류, 중복·동시 요청이 원본 설계와 일치합니다.

### 3.2. 프론트엔드

#### 구현할 기능

`Router → Page mount → features/.../api.js → api/client.js → DOM 표시 → Event → Snapshot 갱신·이동 → cleanup`의 흐름으로 구현합니다.

#### 주요 규칙

- Page에서 URL을 직접 조합하거나 공통 `fetch`를 반복하지 않습니다. Operation ID 이름으로 API 함수를 작성합니다.
- 사용자·서버 문자열은 `textContent`로 표시하고, 오류 분기는 `message`가 아니라 `code`를 사용합니다.
- 정렬·권한·업무 판정은 서버가 최종 결정합니다.
- `required`, `minlength`, `maxlength`, `accept` 등은 API 10절과 일치시킵니다.
- Page 종료 시 Event·Timer·Subscription·Bootstrap Instance를 정리합니다.

#### 작업 대상

`frontend/src/pages/`, `frontend/src/features/`, `frontend/src/api/client.js`, `frontend/src/app/`, `frontend/src/styles/`.

#### 완료 확인

같은 Page가 다시 mount되어도 이벤트가 중복되지 않고, 정상·빈 목록·에러·모바일 상태가 표현됩니다.

### 3.3. 처음 한 번 따라 할 예: `createGroup`

`createGroup`은 `POST /api/v1/groups`이고, 요청은 `CreateGroupRequest`, 결과는 `CreateGroupResponse`입니다. 입력과 성공 상태는 [API 설계](api-design.md)를 확인합니다.

#### 백엔드

##### 작업 대상

`GroupController`, 그룹 생성 Service·Repository, `CreateGroupRequest`, `CreateGroupResponse`, API·Service 테스트. 실제 클래스·메서드 위치는 [클래스 다이어그램](class-diagram.md)을 우선합니다.

##### 처리 순서

1. 인증·그룹 없음·가입 제한을 검사합니다.
2. `name` NFKC·Trim(2~30자), `description`(최대 500자), 전역 중복을 검사합니다.
3. 사용자·그룹 가입 제약과 트랜잭션을 적용합니다.
4. 그룹·주 관리자 멤버십·초대 링크·현재 시즌 그룹 참여를 생성합니다.
5. `201 Created`와 약속된 DTO를 반환합니다.

##### 완료 확인

이름 중복·기존 소속·가입 제한·동시 가입 충돌 시 중복 그룹·멤버십이 생기지 않습니다.

#### 프론트엔드

##### 작업 대상

`frontend/src/pages/group-select-page.js`와 그룹 API 함수, 기존 CSS·Mock 응답.

##### 처리 순서

1. Page에서 그룹 이름·소개를 입력받습니다.
2. `createGroup` API 함수를 호출하고 요청 중 버튼을 비활성화합니다.
3. 성공 시 `/creature`로 이동하고 오류는 `code`·`fieldErrors`로 안내합니다.
4. Page를 떠날 때 Event를 해제합니다.

##### 완료 확인

현재 일부 예제는 생성 성공 후 폼만 초기화할 수 있습니다. **완성 기준은 `/creature` 이동**이므로 현 코드와 설계의 차이를 반드시 확인합니다.

### 3.4. 프론트엔드가 처음이라면: 이 다섯 가지만 먼저 이해하기

| 개념 | 역할 | 프로젝트에서 사용하는 것 |
|---|---|---|
| HTML·DOM | 요소의 생성·선택 | `querySelector`, `textContent` |
| CSS·Bootstrap | 배치·반응형·테마 | Utility, `styles/theme.css` |
| JavaScript·Event | 클릭·입력·제출 제어 | `addEventListener`, `preventDefault` |
| API·Module | 서버 통신·기능 분리 | `export`, `import`, `features/.../api.js` |
| CSR·Mount·Cleanup | URL에 따른 화면 생성·정리 | Router, Page의 `element`·`cleanup` |

**기준:** React·Vue·Thymeleaf를 사용하지 않습니다. 공통 `index.html`의 `#app`에 JS Page를 mount하며 화면마다 `.html`을 늘리지 않습니다.

### 3.5. 첫 실행부터 화면이 뜨기까지

#### ① 어디서 실행할까?

##### 작업 대상

저장소 루트의 `frontend/`와 `package-lock.json`, `vite.config.js`.

##### 처리 순서

```powershell
# 저장소 루트에서 프론트엔드 폴더로 이동합니다.
cd frontend

# package-lock.json에 고정된 버전대로 의존성을 설치합니다.
npm ci

# Vite 개발 서버를 실행합니다.
npm run dev
```

브라우저에서 `http://localhost:5173`을 열어 확인합니다. 백엔드 통합 시 `/api`, `/oauth2`, `/ws` Proxy를 사용합니다.

#### ② 파일은 어떤 순서로 실행될까?

`index.html → src/main.js → app/bootstrap.js → Router → pages/*-page.js → features/*/api.js → api/client.js`를 기준으로 추적합니다. 실제 import 구조가 다르면 실행 중인 코드가 우선이며 새 구조를 임의로 추가하지 않습니다.

#### ③ 현재 실제 폴더는 어디까지 있을까?

##### 주요 규칙

- 이미 존재하는 `frontend/src/pages/`, `features/`, `app/`, `api/`, `styles/`를 먼저 찾습니다.
- 기존 화면 수정이면 새 Router를 만들지 않습니다.
- Mock API 사용 시 `.env.local`의 `VITE_USE_MOCK_API=true`를 적용하고 Vite를 재시작합니다. 운영 빌드에서는 Mock을 사용하지 않습니다.

### 3.6. 실습 A — API 없이 글자 수 표시 기능 추가하기 (첫 실습으로 권장)

#### 구현할 기능

`/groups/select`의 그룹 소개 아래에 **현재 글자 수/500자**를 입력할 때마다 표시합니다. 서버·Router는 수정하지 않습니다.

#### 작업 대상

`frontend/src/pages/group-select-page.js`, `frontend/src/styles/theme.css`.

#### ① 먼저 기존 HTML 요소를 찾아보기

기존 그룹 소개 입력 요소를 `querySelector`로 찾고 이미 존재하는 Label·DOM 구조를 재사용합니다.

#### ② JavaScript로 안내 요소 만들기

`textContent`로 글자 수를 출력하고 `input` 이벤트에서 갱신합니다. 서버의 최종 500자 검증은 그대로 유지합니다.

#### ③ 화면을 떠날 때 이벤트 해제하기

`addEventListener('input', ...)`로 등록했다면 Page `cleanup()`에서 같은 Handler로 `removeEventListener()`를 호출합니다.

#### ④ CSS로 위치와 색상 정하기

Bootstrap Utility를 우선 적용하고 추가 스타일은 테마 변수 기반으로 `theme.css`에 작성합니다.

#### ⑤ 브라우저에서 직접 확인하기

0자·500자·초과·화면 이동 후 복귀를 점검하고 오류 없이 DOM이 갱신되는지 확인합니다.

### 3.7. 실습 B — 백엔드 데이터로 공개 그룹 목록 표시하기

#### 구현할 기능

설계된 `listPublicGroups`(`GET /api/v1/groups`)로 공개 그룹 목록을 표시합니다. 새 엔드포인트나 가입 동작은 추가하지 않습니다.

#### ① 먼저 수정 범위와 데이터 약속 확인하기

##### 작업 대상

`frontend/src/pages/group-select-page.js`, `features/`의 그룹 API 파일, `mocks/mock-api.js`.

**입력 계약:** `PublicGroupsQuery`(기본 `page=0`, `size=20`), 응답은 `GroupSummaryResponse[]`와 Page `meta`입니다.

#### ② 기능별 API 함수 만들기

Page가 직접 `fetch`하지 않고 `listPublicGroups` Operation을 호출하도록 API 함수를 구성합니다. Cookie·CSRF·오류 처리는 공통 Client에 맡깁니다.

#### ③ 백엔드 없이 사용할 목 목록 만들기

최소 두 그룹을 가진 정상 응답, 빈 배열, 업무 오류 응답을 실제 Response Envelope 구조대로 만듭니다.

#### ④ Page에서 목록을 생성하고 그리기

1. Page mount 시 `listPublicGroups`를 호출합니다.
2. DOM 요소는 안전하게 생성하고 그룹 이름은 `textContent`로 넣습니다.
3. 인원·정원·가입 가능 상태를 그립니다.
4. 재조회·화면 전환 시 기존 목록과 Event를 정리합니다.

#### ⑤ 목과 실제 서버를 각각 확인하기

Mock ON/OFF를 바꿔 정상·빈 목록·오류를 확인하고 Network에서 GET URL·응답·Page `meta`를 점검합니다. 현재 `apiRequest()`가 `meta`를 누락한다면 페이지네이션 구현 전에 공통 응답 계약을 조정해야 합니다.

### 3.8. 정말 새로운 화면이 필요할 때만 Router에 등록하기

**구현 원칙:** 화면 내 기능 추가는 기존 Page 수정이고, 새 URL은 [화면 설계 2절](screen-design.md#2-화면-경로)에 정의된 경우에만 Router 등록을 검토합니다.

#### ① Page 파일 만들기

`pages/`에 기존 Page 형식(마운트용 `element`와 `cleanup`)을 따르는 파일을 작성합니다. 서버 문자열은 `textContent`로 렌더링합니다.

#### ② `bootstrap.js`에 Route 추가하기

새 Path·접근 등급·Page import를 등록하고 해당 Route Guard가 `/api/v1/me` 상태를 확인하도록 합니다.

#### ③ 지금 코드와 앞으로 확장할 규칙을 혼동하지 않기

실제 구현된 공통 Router·API Client를 먼저 확인합니다. 아직 설계에만 있는 기능은 이미 구현된 것으로 가정하지 않습니다.

### 3.9. API 오류·이동·화면 정리: 실수하기 쉬운 곳

| 상황 | 지켜야 할 처리 |
|---|---|
| HTTP 통신 | `pages` → `features/.../api.js` → `api/client.js` |
| 사용자·서버 문자열 | `textContent` 사용, 임의 `innerHTML` 금지 |
| Form Submit | `event.preventDefault()` 적용 |
| 중복 클릭 | 요청 중 버튼 비활성화 후 `finally` 복구 |
| 오류 | `ApiError.code`, `status`, `fieldErrors`로 분기 |
| 내부 페이지 이동 | Router `navigate()` 사용 (OAuth 외부 Redirect는 예외) |
| 화면 종료 | Event·Timer·WebSocket 구독·Bootstrap Instance 정리 |
| 프론트 권한 | 사용자 안내일 뿐, Service에서 다시 검사 |
| WebSocket | STOMP 수신 후 REST Snapshot으로 재동기화 |

특히 `createGroup()` 성공 후 폼 초기화만 하는 현재 예제와 최종 `/creature` 이동 요구는 다를 수 있으므로 완성 판단은 설계를 따릅니다.

### 3.10. 초보자를 위한 구현 순서 카드와 문제 해결표

#### 처리 순서

1. 요청 ID·화면 URL·Operation ID·DTO·오류를 읽습니다.
2. 기존 Page인지 새 Page인지 판단합니다.
3. HTML·CSS 최소 골격을 구성하고 Event를 연결합니다.
4. `features/` API 함수와 Mock 정상·빈 목록·실패 데이터를 만듭니다.
5. 실제 서버 연결, `npm run build`, 모바일·테마·키보드 검증 후 PR을 작성합니다.

| 증상 | 우선 확인 |
|---|---|
| 흰 화면 | Console, import, Page `element` |
| Route 404 | `app/bootstrap.js`, Vite 구동 |
| 로그인으로 이동 | `/api/v1/me` 상태·Route 접근 등급 |
| 클릭 무반응·새로고침 | 이벤트 선택자·Handler·`preventDefault()` |
| 변경 미반영 | 파일 경로·Vite 재시작 |
| API 오류 | Network Method·URL, Spring Boot Log |
| Mock 미표시 | `.env.local`, `mockApi` 구현 |
| 응답은 성공인데 목록 없음 | `data` 배열·`meta`·렌더링 로직 |
| 화면 이동 후 중복 실행 | `cleanup()` |
| 다크 모드 대비 저하 | Bootstrap·테마 변수 |

**협업:** `app/router.js`, `app/bootstrap.js`, `api/client.js` 등 공통 파일 변경은 미리 공유하고 담당 기능 브랜치·PR로 반영합니다. `main`에서 직접 작업하지 않습니다.

## 4. 화면별 REST API 연결표

공통 부트스트랩은 `getCurrentUser`, 상태 변경 전에는 `getCsrfToken`, 로그아웃에는 `logout`을 사용합니다.

| 화면 경로 | 최초 조회 Operation | 사용자 동작 Operation | 성공 후 처리 |
|---|---|---|---|
| `/login` | `getCurrentUser` | `createOAuthAttempt` | `authorizationUrl` 이동 |
| `/invite/:token` | `getPublicInvitation` | `acceptInvitation`, `rejectInvitation` | `nextRoute` 이동 |
| `/signup` | `getSignupContext` | `completeSignup` | 상태별 Route Guard |
| `/groups/select` | `listPublicGroups` | `proofreadGroupDescription`, `createGroup`, `joinGroup` | `/creature` 또는 업무 오류 |
| `/creature` | `getCurrentSeason`, `getCreatureHome` | WebSocket Event | Snapshot 재조회 |
| `/quests` | `listCurrentQuests` | `completeQuestAssignment`, `createCheckIn`, `submitQuestPhoto` | 퀘스트 재조회 |
| `/rankings` | `getMemberRanking`, `getGroupRanking` | Page·시즌 변경 | 선택 랭킹 재조회 |
| `/group` | `getMyGroup`, `listMyGroupMembers` | `leaveMyGroup`, `createUserReport` | 탈퇴 시 `/groups/select` / 신고 안내 |
| `/shop` | `listShopItems`, `getShopItem` | `createPurchase`, `verifyPurchasePayment` | 구매·아바타로 이동 |
| `/avatar` | `getAvatar`, `listOwnedItems` | `equipAvatarItem`, `unequipAvatarItem` | `AvatarResponse` 재렌더 |
| `/payments` | `listPurchases`, `listMyRefundRequests` | `getPurchase`, `createRefundRequest` | 구매·환불 재조회 |
| `/group-admin` | `getMyGroup`, `listMyGroupMembers`, `getInvitationManagement`, `listActiveCreatureSpecies` | `changeMemberRole`, `removeGroupMember`, `transferPrimaryAdmin`, `rotateInvitation`, `selectCreatureSpecies` | 관련 Snapshot 재조회 |
| `/admin` | `getAdminDashboard` | 필터 변경 | 카드·차트 갱신 |
| `/admin/reports` | `listAdminReports` | `getAdminReport`, `dismissReport`, `removeReportedMember` | 목록·상세 재조회 |
| `/admin/refunds` | `listAdminRefundRequests` | `getAdminRefundRequest`, `approveRefund`, `rejectRefund`, `reconcileRefund` | 목록·상세 재조회 |
| `/admin/operations` | `listOperationalWarnings` | `transferPrimaryAdminAsSuperAdmin` | 경고·그룹 재조회 |

`handleOAuthCallback`은 OAuth 공급자의 Redirect, `receivePortOneWebhook`은 PortOne의 서버 요청에서 실행되므로 일반 Page 버튼에 연결하지 않습니다.

## 5. 기능별 백엔드 시작점

| 기능 | Operation 범위 | 담당 Service·Port | 우선 확인할 테이블 |
|---|---|---|---|
| 인증·가입 | `getCsrfToken` ~ `logout` | `AuthService`, `SocialLoginClient` | `users`, `social_accounts`, `user_profiles`, `oauth_login_attempts`·Spring Session |
| 초대·그룹 | `getPublicInvitation` ~ `rotateInvitation` | `GroupMembershipService`, `GroupAdminService` | `game_groups`, `group_memberships`, `group_invite_links`, `group_join_restrictions` |
| 시즌·크리처 | `getCurrentSeason` ~ `selectCreatureSpecies` | 시즌·크리처 Service | `seasons`, `creature_species`, `group_seasons`, `group_creatures` |
| 퀘스트 | `listCurrentQuests` ~ `submitQuestPhoto` | 퀘스트·보상 Service | `quest_templates`, `quest_assignments`, `quest_completions`, `quest_reward_ledgers`, `photo_submissions` |
| 랭킹 | `getMemberRanking`, `getGroupRanking` | Ranking Query Service | `season_member_contributions`, `season_group_scores`, Ranking Snapshot |
| 아바타·상점 | `listShopItems` ~ `unequipAvatarItem` | Avatar Service | `shop_items`, `owned_items`, `avatar_equipments` |
| 결제·환불 | `createPurchase` ~ `reconcileRefund` | Payment·Refund Service·`PaymentGateway` | `purchases`, `payment_transactions`, `refund_requests`, `api_idempotency_records` |
| 신고·전체 관리자 | `createUserReport` ~ `listOperationalWarnings` | Admin Service | `user_reports`, `admin_audit_logs`, `scheduler_executions` |

## 6. 목 데이터로 프론트엔드를 먼저 만드는 방법

### 처리 순서

1. API 11절 응답 DTO를 그대로 사용하여 정상 응답 객체를 만듭니다.
2. 목록이면 `data: []`도 만들고, API 9.5절의 업무 오류를 최소 한 종류 구현합니다.
3. `mock-api.js`와 실제 API 함수가 같은 결과·`ApiError` 계약을 유지하게 합니다.
4. Mock 분기는 `VITE_USE_MOCK_API`로 공통 API 경계에만 둡니다. Page는 Mock 여부를 몰라야 합니다.

### 완료 확인

Mock·실제 서버 모두 동일한 Page 코드로 동작하고 오류 문구·빈 목록·Page `meta`가 일치합니다.

## 7. Operation 완료 체크리스트

### 백엔드

- [ ] Operation ID·Method·Path가 API 9절과 같습니다.
- [ ] 10절 Request DTO의 필수값·타입·길이를 검사합니다.
- [ ] Service가 인증·역할·상태를 재검사합니다.
- [ ] UNIQUE·잠금·멱등성·트랜잭션 경계를 반영합니다.
- [ ] 외부 Port 호출은 DB 잠금을 오래 점유하지 않습니다.
- [ ] 성공 HTTP 상태·11절 Response DTO·업무 오류를 테스트했습니다.

### 프론트엔드

- [ ] Page가 공통 Client를 거쳐 Operation 함수를 호출합니다.
- [ ] 정상·로딩·빈 데이터·오류 상태를 표시합니다.
- [ ] 처리 중 중복 클릭을 방지하고 완료 시 Snapshot을 갱신합니다.
- [ ] 권한 안내와 Router 이동이 화면 설계와 일치합니다.
- [ ] Cleanup·모바일·다크 모드·키보드 조작을 확인합니다.

### 문서 정합성

- [ ] 요청 ID·Operation ID·Route·DTO·테이블·화면을 추적할 수 있습니다.
- [ ] 원본에 없는 기능·Enum·필드를 임의로 추가하지 않았습니다.

## 8. HTTPS를 직접 구축하지 않는 통합 검증

### 구현할 기능

평상시 localhost HTTP를 사용하고, 모바일 OAuth·PortOne Redirect·Webhook·WebSocket·EXIF 촬영 검증에만 임시 HTTPS 터널을 사용합니다.

### 처리 순서

1. Spring Boot(백엔드)와 Vite(프론트엔드)를 실행합니다.
2. Vite의 `/api`, `/oauth2`, `/ws` Proxy를 확인합니다.
3. Vite Port 하나를 임시 HTTPS 터널로 공개합니다.
4. `integration` Profile에서 `APP_BASE_URL`에 공개 HTTPS Origin을 넣고 `Secure=true` 쿠키를 사용합니다.
5. 동일 Origin의 OAuth Callback·결제 Redirect·Webhook URL을 공급자 콘솔에 설정합니다.
6. PC·모바일에서 로그인·결제 복귀·Webhook 수신·WebSocket·사진 제출을 검증합니다.

### 완료 확인

URL이 바뀌면 공급자 콘솔도 갱신합니다. `local`에서만 `Secure=false`를 허용하며 인증서 발급·도메인 구매·운영 서버 구축은 범위 밖입니다.
