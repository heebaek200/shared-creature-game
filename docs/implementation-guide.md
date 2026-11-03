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
  → api 함수가 반환한 data를 DOM에 표시
  → 사용자 Event에서 명령 api 호출
  → 성공 시 Snapshot 재조회 또는 설계된 경로로 이동
  → Page cleanup
```

- Page는 URL을 직접 조합해 `fetch`하지 않고 Operation ID와 같은 이름의 `api` 함수를 호출합니다.
- Page는 `features/<기능>/api.js`의 Operation 함수를 사용하고, 공통 `api/client.js`가 Cookie, CSRF, 공통 Envelope와 `ApiError`를 처리합니다.
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
5. 데이터베이스 설계서의 잠금 순서대로 사용자·그룹 관련 상태를 확인하고 `game_groups`, `group_memberships`, `group_invite_links`를 한 트랜잭션에서 생성합니다.
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
2. `features/group/api.js`에 있는 `createGroup(input)`을 사용하고, 실제 JSON·Cookie·CSRF 처리는 공통 `api/client.js`에 맡깁니다.
3. Submit Event에서 중복 클릭을 막고 `createGroup()`을 한 번 호출합니다.
4. 성공하면 `/creature`로 이동합니다. `fieldErrors`는 입력 아래에, `GROUP_NAME_ALREADY_USED`, `ALREADY_IN_GROUP`, `JOIN_COOLDOWN_ACTIVE`는 Form 위의 공통 안내 영역에 표시합니다.
5. Cleanup에서 Submit Event를 제거합니다.
6. 정상 응답, 세 업무 오류와 이름 길이 오류 목 데이터를 확인한 뒤 실제 백엔드와 연결합니다.

### 3.4. 프론트엔드가 처음이라면: 이 다섯 가지만 먼저 이해하기

**프론트엔드는 사용자가 보는 화면을 만들고, 조작에 반응하고, 서버 데이터를 표시하는 코드입니다.** 이 프로젝트에서는 HTML·CSS·JavaScript를 다음처럼 나눠 생각하면 됩니다.

| 용어 | 한 문장 설명 | 이 프로젝트의 예 |
|---|---|---|
| HTML | 화면에 **무엇이 있는지** 정합니다. | 제목, 입력창, 버튼, 목록 |
| CSS | 요소가 **어떻게 보이는지** 정합니다. | 여백, 글자 색, 모바일 배치 |
| JavaScript | 사용자가 행동하면 **무엇을 할지** 정합니다. | 버튼 클릭, 글자 수 갱신, API 요청 |
| DOM | 브라우저가 HTML 요소를 다룰 수 있게 만든 객체입니다. | `querySelector`, `textContent` |
| Event | 클릭·입력·제출처럼 브라우저에서 일어난 사건입니다. | `click`, `input`, `submit` |
| API | 프론트엔드가 서버에 데이터를 요청하는 약속입니다. | `listPublicGroups()`, `createGroup()` |
| Module | 다른 JS 파일에서 함수를 가져다 쓰는 단위입니다. | `export`, `import` |
| SPA / CSR | 새 HTML 문서를 매번 받지 않고 **JS가 화면을 바꾸는** 방식입니다. | `/login` → `/groups/select` |
| Mock | 서버가 없어도 개발할 수 있게 준비한 가짜 응답입니다. | `mocks/mock-api.js` |
| Mount / Cleanup | 화면을 **붙이는 작업 / 떠날 때 정리하는 작업**입니다. | Page의 `element` / `cleanup` |

**중요:** 이 프로젝트는 React·Vue·Thymeleaf 방식이 아닙니다. `index.html`은 앱의 빈 자리인 `<div id="app"></div>`와 `main.js` 연결을 가지고 있습니다. 각 화면의 HTML은 `pages/*.js`에서 DOM으로 생성합니다. 기능을 추가할 때마다 새 `.html` 파일을 만들지 않습니다.

### 3.5. 첫 실행부터 화면이 뜨기까지

#### ① 어디서 실행할까?

저장소를 내려받아 프로젝트의 `frontend` 폴더에서 터미널을 엽니다. Node.js와 npm이 설치되어 있어야 합니다. 현재 저장소의 `package.json`에는 `dev`, `build`, `preview` 스크립트가 있습니다.

```bash
# 저장소 루트에서 프론트엔드 폴더로 이동합니다.
cd frontend

# package-lock.json에 기록된 의존성을 설치합니다.
npm ci

# Vite 개발 서버를 시작합니다.
npm run dev
```

브라우저에서 `http://localhost:5173`으로 접속합니다. 명령어를 실행한 터미널은 개발 서버를 사용하는 동안 열어 둡니다. 저장한 JS·CSS 변경은 보통 새로고침 없이 반영되지만, 화면 상태가 꼬이면 브라우저를 새로고침합니다.

**아직 백엔드 로그인 기능이 준비되지 않았다면** `frontend/.env.local` 파일에 다음 한 줄을 넣습니다.

```dotenv
VITE_USE_MOCK_API=true
```

개발 서버를 다시 실행한 다음 `http://localhost:5173/groups/select`로 접속하면 현재 목 API의 `getCurrentUser()`가 그룹 없는 로그인 사용자 상태를 돌려주므로 기존 그룹 생성 화면을 연습할 수 있습니다. 반대로 실제 서버와 연동할 때는 `VITE_USE_MOCK_API=false`로 바꾸고 Spring Boot도 실행합니다. `.env.local`은 개발자 개인 설정이므로 비밀 키를 넣지 않습니다. **현재 코드의 Google 로그인 시작은 목 구현이 아니라 실제 백엔드 호출**이므로 목 모드에서도 Google 로그인 버튼을 눌러 테스트하는 방식은 아닙니다.

#### ② 파일은 어떤 순서로 실행될까?

```text
frontend/index.html
  └─ <div id="app">와 <script ... src="/src/main.js">
      └─ src/main.js             : Bootstrap CSS·테마 CSS 로드, 앱 시작
          └─ src/app/bootstrap.js: 화면 경로와 접근 등급 등록
              └─ src/app/router.js: URL 판단, 로그인 상태 확인, 화면 교체
                  └─ src/pages/...: HTML 요소 생성, 이벤트 연결
                      └─ src/features/.../api.js: 기능별 API 호출
                          └─ src/api/client.js : 실제 fetch, 세션·CSRF·오류 처리
```

예를 들어 `/groups/select`에 접속하면 라우터는 로그인 상태를 확인한 뒤 `groupSelectPage()`를 호출합니다. 그 함수가 반환한 `element`를 `#app`에 붙여 화면을 보여 줍니다. 다른 화면으로 이동하면 라우터가 이전 페이지의 `cleanup()`을 실행합니다.

#### ③ 현재 실제 폴더는 어디까지 있을까?

```text
frontend/
├── index.html
├── package.json
├── vite.config.js
└── src/
    ├── main.js
    ├── app/
    │   ├── bootstrap.js
    │   └── router.js
    ├── api/
    │   └── client.js
    ├── pages/
    │   ├── login-page.js
    │   ├── signup-page.js
    │   ├── group-select-page.js
    │   ├── status-page.js
    │   └── not-found-page.js
    ├── features/
    │   ├── auth/api.js
    │   └── group/api.js
    ├── mocks/
    │   └── mock-api.js
    └── styles/
        └── theme.css
```

위 구조는 이 문서 작성 시점의 `main` 브랜치에서 **존재가 확인된 파일만** 표시한 것입니다. [코드 컨벤션](convention.md)에는 향후 추가될 `components`, `realtime`, `payment` 등의 권장 폴더도 있지만 아직 모든 폴더가 생성되었다는 뜻은 아닙니다.

### 3.6. 실습 A — API 없이 글자 수 표시 기능 추가하기 (첫 실습으로 권장)

**목표:** 기존 `/groups/select` 화면의 **그룹 소개** 입력창 아래에 `0/500자`를 보여 주고, 입력할 때마다 숫자를 바꿉니다. 서버나 라우터는 수정하지 않습니다.

**수정할 파일은 단 두 개입니다.**

- `frontend/src/pages/group-select-page.js` — HTML 요소 생성 및 이벤트 처리
- `frontend/src/styles/theme.css` — 글자 수 안내의 모양

#### ① 먼저 기존 HTML 요소를 찾아보기

`group-select-page.js`를 열고 `element.innerHTML` 속의 그룹 소개 입력창을 찾습니다. 현재 요소에는 `id="group-description"`, `maxlength="500"`이 있습니다.

```html
<!-- 기존 page.js 템플릿 문자열 안에 있는 요소의 형태를 설명한 예시입니다. -->
<textarea id="group-description" maxlength="500" rows="4"></textarea>
```

`id`는 특정 요소를 찾는 이름표이고, `maxlength`는 브라우저가 입력을 허용할 최대 글자 수입니다. **기존 `<textarea>`를 하나 더 만들지 않습니다.** 아래 JS에서 이미 있는 요소를 찾습니다.

#### ② JavaScript로 안내 요소 만들기

같은 파일의 `const theme = element.querySelector('.theme-toggle');` **바로 아래**에 다음 코드를 추가합니다. `querySelector()`는 기존 요소를 찾고, `createElement()`는 새 요소를 만듭니다.

```javascript
// 기존 그룹 소개 입력창을 선택합니다.
const description = element.querySelector('#group-description');

// 화면에 '현재 글자 수 / 최대 글자 수'를 표시할 요소를 만듭니다.
const counter = document.createElement('small');
counter.className = 'form-text group-description-count';
description.insertAdjacentElement('afterend', counter);

/**
 * 그룹 소개 입력창의 현재 글자 수를 갱신합니다.
 * 최대 글자 수는 HTML의 maxlength 속성에서 읽어 옵니다.
 * 화면 최초 표시와 사용자의 입력 이벤트에 공통으로 사용합니다.
 */
function updateDescriptionCount() {
  // textContent를 사용해 안내 문구를 안전하게 표시합니다.
  counter.textContent =
    `${description.value.length}/${description.maxLength}자`;
}

// 사용자가 한 글자 입력하거나 지울 때마다 갱신합니다.
description.addEventListener('input', updateDescriptionCount);
updateDescriptionCount(); // 최초에는 0/500자를 표시합니다.
```

여기서 `input`은 입력 내용이 바뀔 때 발생하는 이벤트, `addEventListener()`는 그때 실행할 함수를 등록하는 메서드입니다. `` `${...}` ``는 숫자를 문자열 속에 넣는 템플릿 문자열입니다.

#### ③ 화면을 떠날 때 이벤트 해제하기

기존 `groupSelectPage()`의 끝부분에 이미 `cleanup: () => { ... }`가 있습니다. 이 **기존 cleanup 블록 안에** 다음 한 줄을 추가합니다. cleanup 전체를 새로 만들지 않습니다.

```javascript
// 페이지가 사라지면 이번 실습에서 등록한 이벤트도 해제합니다.
description.removeEventListener('input', updateDescriptionCount);
```

기존 `form.removeEventListener(...)`와 `theme.removeEventListener(...)`는 지우지 않습니다. 페이지가 없어졌는데도 이벤트나 타이머가 계속 남아 실행되지 않도록 정리하는 과정입니다.

#### ④ CSS로 위치와 색상 정하기

`frontend/src/styles/theme.css` **맨 아래**에 다음 CSS를 추가합니다.

```css
/* 그룹 소개 글자 수를 입력창 오른쪽 아래에 표시합니다. */
.group-description-count {
  display: block;
  margin-top: 0.25rem;
  text-align: right;
  color: var(--bs-secondary-color);
}
```

CSS의 `.group-description-count`는 JS에서 설정한 `className`과 연결됩니다. Bootstrap의 색상 변수를 사용하므로 라이트·다크 테마에서도 가독성을 유지하기 쉽습니다.

#### ⑤ 브라우저에서 직접 확인하기

1. `/groups/select` 화면에서 처음 `0/500자`가 보이는지 확인합니다.
2. `안녕하세요`를 입력해 숫자가 바뀌는지 확인합니다.
3. 글자를 지우면 숫자가 줄어드는지 확인합니다.
4. 개발자 도구(`F12`)의 **Console**에 붉은 오류가 없는지 확인합니다.
5. 테마 전환 버튼을 눌러도 글자 수가 읽히는지 확인합니다.

**이 실습은 `pages/`와 `styles/`만 수정합니다.** 기존 페이지에 버튼, 안내 문구, 입력 반응을 추가하는 기능도 같은 방식으로 시작할 수 있습니다.

### 3.7. 실습 B — 백엔드 데이터로 공개 그룹 목록 표시하기

**목표:** 설계에 이미 존재하는 `listPublicGroups`(`GET /api/v1/groups`)를 사용해 `/groups/select`에 공개 그룹 목록을 보여 줍니다. 새 URL을 만들지 않으며, **목록 조회 부분만** 실습합니다. 그룹 가입 버튼과 검색·페이지네이션까지 완성하는 예제는 아닙니다.

#### ① 먼저 수정 범위와 데이터 약속 확인하기

[REST API 설계서](api-design.md)의 9.2절과 11.2절에 따르면 `listPublicGroups`의 성공 `data`는 `GroupSummaryResponse[]`입니다. 항목에는 `groupId`, `name`, `description`, `currentMemberCount`, `capacity`, `remainingCapacity`, `joinable`, `joinBlockedReason` 등이 있습니다. Query는 `q`, `page`, `size`를 사용하며 기본 `page=0`, `size=20`입니다. 목록 순서는 **서버가 결정**합니다.

| 해야 할 일 | 수정할 파일 |
|---|---|
| API 호출 함수 추가 | `frontend/src/features/group/api.js` |
| 서버 대신 돌려줄 목 목록 추가 | `frontend/src/mocks/mock-api.js` |
| 화면 요소와 목록 렌더링 추가 | `frontend/src/pages/group-select-page.js` |
| 필요할 때만 화면 꾸미기 | `frontend/src/styles/theme.css` |

**`frontend/src/api/client.js`는 수정하지 않습니다.** 여기에는 이미 HTTP `fetch`, 세션 Cookie, CSRF, 공통 오류 처리가 들어 있습니다.

#### ② 기능별 API 함수 만들기

`frontend/src/features/group/api.js`에는 기존 `createGroup()`과 `useMock`이 있습니다. 기존 코드를 남겨 두고 아래 함수를 **추가**합니다.

```javascript
/**
 * 공개 그룹 목록을 조회합니다.
 * 현재 실습은 첫 페이지의 기본 20개 항목만 표시합니다.
 * 실제 HTTP 호출과 목 응답의 선택은 이 함수에서 담당합니다.
 */
export function listPublicGroups() {
  // 목 모드라면 서버 통신 없이 가짜 그룹 목록을 반환합니다.
  return useMock
    ? mockApi.listPublicGroups()
    : apiRequest('/api/v1/groups?page=0&size=20');
}
```

`export`는 다른 파일에서도 이 함수를 불러 쓸 수 있게 합니다. `useMock`은 같은 파일에 이미 선언되어 있으므로 다시 선언하지 않습니다. 추후 검색이나 페이지 이동을 구현할 때만 설계의 `q`, `page`, `size`에 맞게 입력과 Query를 확장합니다.

**주의:** 현재 공통 `apiRequest()`는 서버의 `{ data, meta }` 중 `data`만 반환합니다. 따라서 `listPublicGroups()`의 반환값은 **배열 자체**입니다. 예를 들어 `groups.data`가 아니라 `groups`를 반복 처리합니다. 총 페이지 수 등 `meta`가 필요한 페이지네이션은 공통 클라이언트의 응답 방식에 관한 팀 합의 후 추가해야 합니다.

#### ③ 백엔드 없이 사용할 목 목록 만들기

`frontend/src/mocks/mock-api.js`에서 기존 `mockApi = { ... }` 객체 **안에** `listPublicGroups` 메서드를 추가합니다. 이미 있는 `getCurrentUser`, `createGroup` 메서드는 지우지 않습니다.

```javascript
/**
 * 공개 그룹 목록 조회의 정상 응답을 흉내 냅니다.
 * 필드 이름과 타입은 GroupSummaryResponse를 따릅니다.
 * 실제 서버가 준비되기 전 목록 렌더링을 확인할 때 사용합니다.
 */
async listPublicGroups() {
  // 실제 요청처럼 아주 짧은 지연을 주어 로딩 표시도 확인합니다.
  await delay(250);
  return [
    {
      groupId: 501,
      name: '새싹 모임',
      description: '함께 크리처를 키워요.',
      currentMemberCount: 3,
      capacity: 20,
      remainingCapacity: 17,
      joinable: true,
      joinBlockedReason: null,
    },
  ];
},
```

위 코드는 독립된 파일이 아니라 **객체 안에 넣는 메서드 조각**입니다. `mockApi` 안의 다른 메서드와 마찬가지로 끝에 쉼표가 있어야 합니다. 가짜 응답을 정상 → 빈 목록(`return []`) → 오류(`throw new ApiError(...)`)로 바꿔 가며 테스트할 수 있습니다. 사용한 그룹명과 숫자는 **연습용 가짜 데이터**입니다.

#### ④ Page에서 목록을 생성하고 그리기

먼저 `frontend/src/pages/group-select-page.js`의 import를 다음처럼 변경합니다.

```javascript
// 기존 createGroup도 계속 사용하므로 함께 import합니다.
import { createGroup, listPublicGroups } from '../features/group/api.js';
```

그런 다음 `const theme = element.querySelector('.theme-toggle');` **아래에** 다음 코드를 추가합니다. 실습 A도 적용했다면 글자 수 코드와 아래 코드는 같은 위치에 나란히 넣어도 됩니다.

```javascript
// HTML 구조는 고정된 문자열로 만들고, 서버 값은 나중에 textContent로 넣습니다.
const listSection = document.createElement('section');
listSection.className = 'app-card mt-3';
listSection.innerHTML = `
  <h2 class="h5">공개 그룹 목록</h2>
  <p class="group-list-status" role="status"></p>
  <ul class="list-group"></ul>
`;
element.append(listSection);

const listStatus = listSection.querySelector('.group-list-status');
const list = listSection.querySelector('.list-group');
let isActive = true; // 페이지를 떠난 뒤 늦게 도착한 응답을 무시하기 위한 표시

/**
 * 공개 그룹 목록을 가져와 DOM에 그립니다.
 * 로딩·빈 결과·정상 결과·통신 오류를 서로 구분합니다.
 * 화면을 떠난 뒤에는 이전 요청 결과로 화면을 갱신하지 않습니다.
 */
async function renderPublicGroups() {
  // 요청이 진행 중임을 사용자에게 알려 줍니다.
  listStatus.textContent = '그룹 목록을 불러오는 중입니다.';
  try {
    const groups = await listPublicGroups();
    if (!isActive) return;
    list.replaceChildren(); // 지난 목록을 지우고 새로 표시합니다.

    if (groups.length === 0) {
      listStatus.textContent = '현재 가입 가능한 공개 그룹이 없습니다.';
      return;
    }

    listStatus.textContent = '';
    for (const group of groups) {
      // 서버에서 받은 글자는 HTML로 해석하지 않고 텍스트로 표시합니다.
      const item = document.createElement('li');
      item.className = 'list-group-item';
      item.textContent =
        `${group.name} — ${group.currentMemberCount}/${group.capacity}명`;
      list.append(item);
    }
  } catch (error) {
    if (!isActive) return;
    listStatus.textContent = error.message ?? '그룹 목록을 불러오지 못했습니다.';
  }
}

// 페이지가 열리면 최초 목록을 조회합니다.
void renderPublicGroups();
```

`await`는 API 작업의 결과가 도착할 때까지 **현재 함수의 다음 단계**를 기다리는 문법입니다. 브라우저 전체가 멈추는 것은 아닙니다. `for...of`는 목록의 각 그룹을 순서대로 화면에 추가합니다. `textContent`를 사용하는 이유는 서버 문자열에 HTML 태그가 섞여 있어도 코드로 실행되지 않게 하기 위해서입니다.

기존 `cleanup: () => { ... }` 안에도 아래 코드를 추가합니다.

```javascript
// 페이지를 떠난 뒤 API 결과가 도착해도 더는 화면을 변경하지 않습니다.
isActive = false;
```

이 실습은 첫 페이지 조회만 사용하므로 목록 검색, 페이지네이션, `joinGroup()` 명령과 상세 오류별 처리는 별도 작업으로 남깁니다. 화면 설계서를 보지 않고 새 가입 버튼이나 임의의 URL을 추가하지 않습니다.

#### ⑤ 목과 실제 서버를 각각 확인하기

1. `.env.local`에서 `VITE_USE_MOCK_API=true`로 설정하고 개발 서버를 재시작합니다.
2. `/groups/select`에서 `새싹 모임 — 3/20명`이 보이는지 확인합니다.
3. `mockApi.listPublicGroups()`를 `return []`로 바꾸고 빈 목록 문구가 보이는지 확인합니다.
4. 목 데이터를 원래대로 돌려놓은 뒤 `VITE_USE_MOCK_API=false`로 바꿉니다.
5. Spring Boot를 실행하고 인증된 세션으로 같은 화면에 접속합니다. 개발자 도구 **Network**에서 `GET /api/v1/groups?page=0&size=20`을 확인합니다.
6. 정상 응답뿐 아니라 401·403·서버 오류가 발생할 때도 화면이 안전하게 반응하는지 확인합니다.

**디버깅 순서:** `Network`에 요청이 없으면 `pages`의 호출과 `features/group/api.js`를 확인하고, 요청은 있는데 404·500이면 주소와 백엔드를 확인합니다. 요청은 200인데 목록이 비면 서버 응답의 `data` 구조를 확인합니다.

### 3.8. 정말 새로운 화면이 필요할 때만 Router에 등록하기

**기존 화면에 요소를 추가하는 것과 새로운 화면을 만드는 것은 다릅니다.** 기존 `/groups/select` 안에 기능을 추가했다면 Router 등록은 필요 없습니다. 반면 화면 설계서에 정의된 `/quests`를 구현할 때는 새 Page 파일과 Route 등록이 필요합니다.

#### ① Page 파일 만들기

예를 들어 아래 파일을 새로 만듭니다.

`frontend/src/pages/quests-page.js`

```javascript
/**
 * 퀘스트 화면의 가장 작은 뼈대를 만듭니다.
 * 현재 예제는 서버 통신 없이 정적 화면만 출력합니다.
 * 이후 listCurrentQuests 등 설계된 Operation을 연결합니다.
 */
export function questsPage() {
  // 화면 전체를 담을 main 요소를 만듭니다.
  const element = document.createElement('main');
  element.className = 'app-shell';
  element.innerHTML = `
    <section class="app-card">
      <h1>퀘스트</h1>
      <p>퀘스트 목록을 준비하고 있습니다.</p>
    </section>
  `;

  // 등록한 이벤트나 구독이 아직 없으므로 정리할 작업도 없습니다.
  return { element };
}
```

이것은 라우트가 열리는지만 확인하는 **화면 뼈대**입니다. 퀘스트의 실제 규칙과 응답 필드는 [화면 설계서](screen-design.md), [REST API 설계서](api-design.md)를 확인한 뒤 추가합니다.

#### ② `bootstrap.js`에 Route 추가하기

`frontend/src/app/bootstrap.js`의 기존 import 목록에 아래를 추가합니다.

```javascript
// 퀘스트 Page 함수를 불러옵니다.
import { questsPage } from '../pages/quests-page.js';
```

같은 파일의 `createRouter(root, [ ... ])` 배열 안에 아래 항목을 추가합니다.

```javascript
// 퀘스트는 활성 그룹 회원이 접근할 수 있는 화면입니다.
{ path: '/quests', access: 'GROUP_MEMBER', page: questsPage },
```

그런 다음 그룹에 가입된 계정으로 `/quests`에 접속해 화면을 확인합니다. 권한이 없으면 라우터가 다른 페이지로 보낼 수 있습니다. **브라우저 주소만 바꿔 접근 제한을 통과하는 것은 올바른 테스트가 아닙니다.**

#### ③ 지금 코드와 앞으로 확장할 규칙을 혼동하지 않기

현재 `router.js`가 Page에 전달하는 값은 `{ navigate }`이고, Page는 `{ element, cleanup? }`을 반환합니다. 현재 구현에 없는 `root`, `params`, `query`, `signal`, `currentUser` 등을 이미 지원한다고 가정하면 안 됩니다. [코드 컨벤션](convention.md)에 적힌 확장 PageContext와 실제 구현이 달라지는 부분은 공통 Router를 담당하는 팀원과 먼저 합의합니다. 권한 등급도 현재 라우터에 실제 구현된 범위에서 확인합니다.

### 3.9. API 오류·이동·화면 정리: 실수하기 쉬운 곳

- **`fetch()`를 Page마다 작성하지 않습니다.** Page → `features/기능/api.js` → `api/client.js`의 순서로 호출합니다. 공통 클라이언트에서 세션 Cookie, CSRF, JSON, 오류 변환을 처리합니다.
- **서버 응답을 그대로 `innerHTML`에 넣지 않습니다.** 사용자·서버 데이터는 `textContent`, `value` 같은 안전한 속성으로 넣습니다. `innerHTML`에는 개발자가 직접 작성한 고정 마크업만 사용합니다.
- **Submit 이벤트에서 `event.preventDefault()`를 잊지 않습니다.** 그렇지 않으면 기본 Form 제출로 페이지가 새로고침될 수 있습니다.
- **중복 요청을 막습니다.** 실제 그룹 생성 화면처럼 요청 중에는 제출 버튼을 `disabled = true`로 바꾸고 `finally`에서 복구합니다.
- **오류를 `message` 문자열 비교로 분기하지 않습니다.** 공통 `ApiError`의 `code`, `status`, `fieldErrors`를 기준으로 처리합니다. 사용자에게 보여 줄 때는 `message`를 사용합니다.
- **화면을 이동할 때는 기존 Router의 `navigate('/경로')`를 사용합니다.** 외부 OAuth 인가 화면으로 나갈 때처럼 설계된 예외를 제외하고 `location.assign()`으로 SPA 내부 화면을 이동시키지 않습니다.
- **이벤트·타이머·구독은 정리합니다.** `addEventListener`를 했다면 `removeEventListener`, `setInterval`을 했다면 `clearInterval`, WebSocket을 구독했다면 해당 Subscription 해제 등을 `cleanup`에서 수행합니다.
- **브라우저의 허용 여부는 보안 검사가 아닙니다.** 버튼이 숨겨져 있어도 Service가 로그인·역할·그룹 소속을 다시 검증해야 합니다.
- **WebSocket은 별도 단계입니다.** 설계상 `/ws`의 STOMP 구독과 서버 Snapshot 재조회가 필요하며 단순 `fetch()`로 대체하지 않습니다. 현재 예제에는 실시간 연결 코드를 새로 도입하지 않습니다.

**기억할 예외:** 현재 `group-select-page.js`는 `createGroup()` 성공 후 초대 링크 메시지를 보여 주고 폼을 초기화합니다. 반면 3.3절과 [화면 설계서](screen-design.md)는 성공 후 `/creature` 이동을 요구합니다. 따라서 **현재 동작 = 최종 완료 규칙이 아닙니다.** 해당 Operation의 구현을 완성할 때 이 차이를 수정해야 합니다. 또 현재 `apiRequest()`는 `meta`를 전달하지 않으므로 목록의 실제 페이지네이션은 추가 검토가 필요합니다.

### 3.10. 초보자를 위한 구현 순서 카드와 문제 해결표

**기능을 하나 맡았다면 아래 카드만 따라가도 작업이 시작됩니다.**

- [ ] **1. 설계 읽기:** 요청 ID, 화면 URL, Operation ID, API Method·Path·DTO, 성공·실패를 확인합니다.
- [ ] **2. 화면 선택:** 기존 `pages/*.js`를 고칠지, 설계에 있는 새 Page를 만들지 결정합니다.
- [ ] **3. HTML 골격:** 제목·입력창·버튼·결과 영역을 만듭니다. 기존 `#app`은 수정하지 않습니다.
- [ ] **4. CSS 최소화:** Bootstrap 클래스를 먼저 쓰고, 부족한 부분만 `styles/`에 추가합니다.
- [ ] **5. 이벤트 연결:** `addEventListener()`로 사용자 동작을 처리하고 종료 시 해제합니다.
- [ ] **6. 데이터 연결:** 필요하면 `features/기능/api.js`에 Operation 함수를 추가합니다.
- [ ] **7. 목으로 확인:** 정상·빈 목록·오류·로딩을 확인합니다.
- [ ] **8. 실제 서버 확인:** 목을 끄고 Network와 Console을 검사합니다.
- [ ] **9. 마무리:** `npm run build`, 모바일·테마·키보드 확인 후 PR을 작성합니다.

| 증상 | 먼저 확인할 것 |
|---|---|
| 화면이 하얗게 나옵니다. | Console 오류, JS import 경로, `element` 반환 여부 |
| URL로 이동했는데 404가 나옵니다. | `app/bootstrap.js`의 Route 등록과 Vite 서버 실행 상태 |
| 로그인 화면으로 되돌아갑니다. | `getCurrentUser()`의 인증 상태와 Route의 `access` 설정 |
| 버튼을 눌렀는데 아무 일도 없습니다. | 선택자(`querySelector`), 이벤트 종류, 이벤트 등록 위치 |
| 버튼을 누르니 페이지가 새로고침됩니다. | Form `submit`에서 `event.preventDefault()` 호출 여부 |
| 저장해도 바뀌지 않습니다. | 수정한 파일 경로, 개발 서버, Console·강력 새로고침 |
| API가 호출되지 않습니다. | `pages` → `features/.../api.js` import와 함수 호출 여부 |
| API가 404·500을 반환합니다. | Network의 Request URL·Method, Spring Boot 로그 |
| 목 데이터가 보이지 않습니다. | `.env.local`, Vite 재시작, `mockApi` 메서드 구현 여부 |
| 200인데 목록이 비어 있습니다. | 응답 `data`의 배열 구조, `apiRequest()`의 `data` 추출, 렌더 함수 |
| 화면을 옮긴 뒤 이상하게 중복 실행됩니다. | `cleanup()`의 Event·Timer·Subscription 해제 여부 |
| 글자나 색상이 다크 모드에서 안 보입니다. | 하드코딩된 색상 대신 Bootstrap 테마 변수 사용 여부 |

**팀 작업 원칙:** 자신의 기능 파일에서는 자유롭게 작업하되 `app/router.js`, `app/bootstrap.js`, `api/client.js`처럼 모두에게 영향을 주는 공통 파일은 수정 전 팀원에게 알립니다. `main`에 바로 작업하지 않고 담당 기능 브랜치에서 구현·테스트 후 PR을 올립니다. **이 가이드는 구현 방법을 설명하는 문서이며, 새 API나 업무 규칙을 승인하는 문서는 아닙니다.**

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
| 초대·그룹 | `getPublicInvitation` ~ `rotateInvitation` | `GroupMembershipService`, `GroupAdminService` | `game_groups`, `group_memberships`, `group_invite_links`, `group_join_restrictions` |
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
