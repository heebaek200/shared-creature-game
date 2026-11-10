# 화면 설계서

## 1. 공통 방향

### 구현할 기능

Vanilla JavaScript ES Module·Vite·Bootstrap v5 기반 Full CSR 화면과 History API Router를 구현합니다.

### 주요 규칙

- 새로고침 후에도 같은 URL의 화면을 복원합니다.
- `/api/v1/me`로 계정·그룹·권한 상태를 확인하고 Route Guard가 화면을 안내합니다. **최종 권한 검사는 서버 API가 수행**합니다.

### 작업 대상

`frontend/src/app/router.js`, `frontend/src/app/bootstrap.js`, `frontend/src/pages/`, 공통 API Client.

### 완료 확인

직접 URL 접근·새로고침·비인가 접근 시 올바른 화면이 표시되고, 서버 권한 검사를 우회할 수 없습니다.

## 2. 화면 경로

와이어프레임의 **미리보기**는 GitHub Pages에 배포된 HTML을 열고, **HTML 소스**는 저장소 내 파일을 엽니다. 미리보기 URL은 GitHub Pages가 `main` 브랜치의 `/docs` 디렉터리를 게시하고, 프로젝트 사이트 주소가 `https://heebaek200.github.io/shared-creature-game/`인 경우를 전제로 합니다. 현재 배포 설정 및 실제 공개 여부는 미확인 상태입니다. 배포 전에는 로컬의 `docs/design/wireframes/index.html`을 브라우저에서 열어 확인합니다.

| 접근 구분 | 경로 | 요청 ID | 구현할 기능 | 와이어프레임 |
|---|---|---|---|---|
| 공개 | `/login` | `REQ-AUTH-001`, `REQ-AUTH-003`, `REQ-AUTH-004` | Google·Naver 로그인 | [로그인 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/login.html) · [HTML 소스](design/wireframes/login.html) |
| 공개 | `/invite/:token` | `REQ-INV-001` | 초대 확인·수락·거절 | [초대 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/invite.html) · [HTML 소스](design/wireframes/invite.html) |
| 가입 진행 | `/signup` | `REQ-AUTH-002`, `REQ-AUTH-003`, `REQ-INV-001` | 닉네임 확정·가입 완료 | [회원가입 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/signup.html) · [HTML 소스](design/wireframes/signup.html) |
| 그룹 없음 | `/groups/select` | `REQ-AUTH-002`, `REQ-GRP-001`, `REQ-GRP-002`, `REQ-AI-001` | 공개 그룹 가입·생성·소개 교정 | [그룹 선택 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/group-select.html) · [HTML 소스](design/wireframes/group-select.html) |
| 그룹 회원 | `/creature` | `REQ-SEA-001`, `REQ-SEA-002`, `REQ-SEA-003`, `REQ-QST-002`, `REQ-AVA-002`, `REQ-RT-001` | 크리처 홈·Presence | [크리처 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/creature.html) · [HTML 소스](design/wireframes/creature.html) |
| 그룹 회원 | `/quests` | `REQ-QST-001`, `REQ-QST-002`, `REQ-QST-003`, `REQ-QST-004` | 일일·주간 퀘스트·사진 제출 | [퀘스트 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/quests.html) · [HTML 소스](design/wireframes/quests.html) |
| 그룹 회원 | `/rankings` | `REQ-RNK-001`, `REQ-RNK-002` | 시즌 개인·그룹 랭킹 | [랭킹 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/rankings.html) · [HTML 소스](design/wireframes/rankings.html) |
| 그룹 회원 | `/group` | `REQ-GRP-002`, `REQ-GRP-003`, `REQ-GRP-006` | 그룹 현황·탈퇴·신고 | [그룹 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/group.html) · [HTML 소스](design/wireframes/group.html) |
| 로그인 회원 | `/shop` | `REQ-AVA-001`, `REQ-PAY-001` | 상품·테스트 결제 | [상점 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/shop.html) · [HTML 소스](design/wireframes/shop.html) |
| 로그인 회원 | `/avatar` | `REQ-AVA-001` | 아바타 아이템 장착 | [아바타 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/avatar.html) · [HTML 소스](design/wireframes/avatar.html) |
| 로그인 회원 | `/payments` | `REQ-PAY-001`, `REQ-PAY-002` | 결제·환불 이력과 요청 | [결제 내역 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/payments.html) · [HTML 소스](design/wireframes/payments.html) |
| 그룹 관리자 | `/group-admin` | `REQ-INV-002`, `REQ-GRP-004`, `REQ-GRP-005`, `REQ-SEA-002` | 구성원·권한·초대·종 선택 | [그룹 관리 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/group-admin.html) · [HTML 소스](design/wireframes/group-admin.html) |
| 전체 관리자 | `/admin` | `REQ-ADM-001` | 운영 대시보드 | [대시보드 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/admin-dashboard.html) · [HTML 소스](design/wireframes/admin-dashboard.html) |
| 전체 관리자 | `/admin/reports` | `REQ-GRP-006`, `REQ-ADM-002` | 신고 검토·강퇴 | [신고 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/admin-reports.html) · [HTML 소스](design/wireframes/admin-reports.html) |
| 전체 관리자 | `/admin/refunds` | `REQ-PAY-002`, `REQ-ADM-003` | 환불 승인·거절·복구 | [환불 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/admin-refunds.html) · [HTML 소스](design/wireframes/admin-refunds.html) |
| 전체 관리자 | `/admin/operations` | `REQ-GRP-004`, `REQ-SEA-001`, `REQ-QST-001`, `REQ-ADM-004` | 시즌·Scheduler·운영 경고 | [운영 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/admin-operations.html) · [HTML 소스](design/wireframes/admin-operations.html) |
| 오류 | `/403` | 권한 제한 요청 | 접근 거부 | [오류 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/errors.html) · [HTML 소스](design/wireframes/errors.html) |
| 오류 | `*` | 해당 없음 | 404 | [오류 미리보기](https://heebaek200.github.io/shared-creature-game/design/wireframes/errors.html) · [HTML 소스](design/wireframes/errors.html) |

그룹 회원의 기본 경로는 `/creature`, 전체 관리자의 관리 기본 경로는 `/admin`입니다. `REQ-UI-001`·`REQ-UI-002`는 전체 화면, `REQ-AUTH-004`는 Route Guard 전체에 적용합니다.

## 3. Route Guard와 기본 이동

### 주요 규칙

| 현재 상태·접근 조건 | 처리 |
|---|---|
| `ANONYMOUS + NOT_STARTED`가 보호 경로 요청 | `/login` 이동 |
| `SIGNUP_PENDING + SIGNUP_REQUIRED` | `/signup` 이동 |
| `AUTHENTICATED + GROUP_REQUIRED`로 그룹 전용 경로 접근 | `/groups/select` 이동 |
| 그룹 없는 인증 회원이 `/shop`, `/avatar`, `/payments` 접근 | 그대로 허용 |
| 활성 그룹 회원이 가입 전용 경로 접근 | `/creature` 이동 |
| `SUPER_ADMIN`의 `/admin/**` 접근 | 그룹 소속 없어도 허용 |
| 인증 회원이 권한 부족 | `/403` |
| 일치하지 않는 경로 | 404 |

접근 등급은 `PUBLIC`, `AUTHENTICATED`, `GROUP_MEMBER`, `GROUP_ADMIN`, `SUPER_ADMIN`입니다. `/group-admin`은 `PRIMARY`·`DEPUTY`, `/admin/**`는 `SUPER_ADMIN`만 이용합니다.

### 처리 순서

1. Route 진입 시 `/api/v1/me` 상태를 평가합니다.
2. 권한 부족·온보딩 미완료면 표에 따른 경로로 안내합니다.
3. `returnUrl`은 허용된 내부 CSR 경로만 사용하고, OAuth·초대 문맥은 서버에 둡니다.

### 완료 확인

24시간 가입 제한 중에도 그룹 선택 화면은 표시하되 가입·생성은 비활성화합니다. 최초 공개 시각과 첫 시즌 시작 시각이 같으므로 프리시즌 Route는 만들지 않습니다.

## 4. 소셜 로그인·회원가입과 그룹 선택

### 구현할 기능

로그인 → OAuth 가입 대기 → 닉네임 확정 → 그룹 선택 또는 초대 수락 흐름을 제공합니다.

### 주요 규칙

- Google·Naver만 지원하고 이메일·비밀번호 입력은 없습니다. 다른 공급자의 동일 이메일은 자동 연결하지 않습니다.
- 신규 OAuth Callback은 **계정을 만들지 않고** 30분 가입 대기 문맥만 유지합니다. 만료 시 다시 인증합니다.
- 초대 수락 후에도 가입 직전에 링크·정원·소속·24시간 제한을 재검증합니다. 실패해도 계정은 생성하며 그룹 선택으로 이동합니다.
- 기존 회원의 초대 수락은 재가입 없이 직접 그룹에 가입시킵니다.
- 이메일 미제공·OAuth 시작 세션 불일치·동시 로그인 3회 초과는 각각 이유를 안내합니다.

### 작업 대상

`/login`, `/invite/:token`, `/signup`, `/groups/select`, `getCurrentUser`, `getSignupContext`, `completeSignup`, 초대 관련 Operation.

### 완료 확인

정상 가입, 30분 만료, 다른 공급자 동일 이메일, 무효 초대, 정원 초과, OAuth 세션 불일치가 각각 구분됩니다.

## 5. 관리자 대시보드

### 구현할 기능

서버 집계 데이터를 Google Charts와 요약 카드·표로 표시합니다.

### 주요 규칙

| 구성 | 표시 기준 |
|---|---|
| 요약 카드 | 활동 사용자, 활성 그룹·정원율, 퀘스트 완료율, 테스트 결제 금액, 환불·신고·경고 |
| Line | 최근 14일 활동 사용자·퀘스트 완료 |
| Column | 현재 시즌 크리처 성장 단계 분포 |
| Bar | 그룹 점수 상위 10개 |
| Combo | 최근 30일 결제·환불 |
| Donut | 그룹 정원 사용률 분포 |
| 필터 | KST 7일·30일·현재 시즌, 전체·특정 그룹 |

### 처리 순서

1. 입장 시 `getAdminDashboard`를 호출합니다.
2. Tab이 보이는 동안 **60초마다** 갱신하며 수동 갱신을 지원합니다.
3. 차트 로드 실패에도 카드·표는 표시합니다. 운영 경고는 현재 미해결 항목만 표시합니다.

### 완료 확인

브라우저가 원본 데이터를 재집계하지 않으며, 마지막 집계 시각·차트 대체 표·관리 화면 이동이 동작합니다.

## 6. 공통 레이아웃과 반응형 내비게이션

### 구현할 기능

데스크톱 좌측 사이드바·상단 헤더, 모바일 상단 헤더·하단 5개 메뉴를 제공합니다.

### 주요 규칙

| 화면 | 구성 |
|---|---|
| 모바일 하단 | `크리처`, `퀘스트`, `랭킹`, `그룹`, `더보기` |
| `더보기` | 상점·아바타·결제 내역 |
| `PRIMARY`, `DEPUTY` | 그룹 관리 메뉴 노출 |
| 전체 관리자 | 별도 관리 사이드바 |
| 공통 헤더 | 현재 그룹·시즌 남은 기간·실시간 연결 상태·테마 전환 |

### 완료 확인

관리자 차트와 표가 좁은 화면에서도 읽히고, 그룹 관리 메뉴 노출이 역할에 따라 달라집니다.

## 7. 색상과 다크 모드

시각 방향은 자연·성장·편안함입니다. 테마별 원본 색상 토큰은 그대로 유지합니다.

### 7.1. 라이트 테마 토큰

| 토큰 | 색상 | 사용처 |
|---|---|---|
| `--color-primary` | `#16836F` | 주요 버튼·활성 메뉴 |
| `--color-secondary` | `#A8DBC1` | 보조 영역 |
| `--color-accent` | `#FFD17B` | 보상·강조 |
| `--color-background` | `#F3FAF6` | 전체 배경 |
| `--color-surface` | `#FFFFFF` | 카드·패널 |
| `--color-text` | `#18332D` | 본문 |
| `--color-text-muted` | `#5B716B` | 보조 설명 |
| `--color-border` | `#CFE4DA` | 테두리 |

`#16836F` 위 흰색 문자 명암비는 약 4.66:1이며, 노란 강조색에는 어두운 본문색을 사용합니다.

### 7.2. 다크 테마 토큰

| 토큰 | 색상 | 사용처 |
|---|---|---|
| `--color-primary` | `#5FC3AA` | 주요 버튼·활성 상태 |
| `--color-secondary` | `#78B89A` | 보조 영역 |
| `--color-accent` | `#FFD17B` | 강조 |
| `--color-background` | `#0E1D19` | 배경 |
| `--color-surface` | `#152A24` | 카드 |
| `--color-surface-raised` | `#1D3831` | Modal |
| `--color-text` | `#EAF7F1` | 본문 |
| `--color-text-muted` | `#A9C2B8` | 보조 설명 |
| `--color-border` | `#315047` | 테두리 |

**주요 규칙:** `시스템 설정`·`라이트`·`다크` 3단계 선택을 `localStorage`에 기록하고 렌더링 전에 `data-theme`을 설정합니다. 차트는 테마 변경 시 다시 그립니다. 상태는 색상과 문구·아이콘을 함께 쓰고, 키보드 포커스·텍스트 명암비 4.5:1·`prefers-reduced-motion`을 지원합니다.

## 8. 크리처 홈

### 구현할 기능

크리처 성장 상태·시즌 시각·그룹 기여·최대 20명 아바타 Presence를 보여 줍니다.

### 주요 규칙

- 사용자의 아바타를 식별자 기반 안정된 구역에 배치하고 중첩·정보 가림을 피합니다. 자신은 `나`로 표시합니다.
- 다른 아바타를 선택하면 닉네임·순위·기여도·장착 아이템과 판매 상품 이동을 제공합니다.
- 알 상태에서는 부화까지 남은 시간을, 부화 후에는 시즌 종료까지 남은 시간을 서버 시각 기준으로 표시합니다. 종 미선택 알은 회색, 선택 완료 알은 해당 종의 색상으로 표시합니다.
- 완전체 이후에도 누적 경험치는 증가합니다. 애니메이션 축소 설정에서는 정적 배치를 사용합니다.

### 처리 순서

1. `getCreatureHome` Snapshot으로 초기 렌더링합니다.
2. Commit 후 WebSocket 이벤트를 수신해 상태를 갱신합니다.
3. 재연결 시 REST Snapshot으로 Presence·장착 상태를 교정합니다.

### 완료 확인

성장·기여 변경, 접속·퇴장, 장착 변화가 반영되고 재연결 후 잘못된 접속 표시가 남지 않습니다.

## 9. 퀘스트 화면

### 구현할 기능

일일·주간 퀘스트, 완료·체크인, EXIF 사진 업로드를 제공합니다.

### 주요 규칙

| 항목 | 조건·표시 |
|---|---|
| 상태 | 진행 가능·처리 중·완료·기간 종료 |
| 중복 클릭 | 처리 중 제출 버튼 비활성화, 기존 완료 응답이면 상태 동기화 |
| 사진 | JPEG·최대 10 MiB, `accept=image/jpeg`는 안내용 |
| HEIC·HEIF | 자동 변환하지 않고 JPEG 재촬영·내보내기 안내 |
| 판정 | GPS·촬영 시각의 유효성만 표시, 실제 실외 활동 검증이라고 주장하지 않음 |

### 완료 확인

유효 제출과 GPS·시각 누락, JPEG 외 형식, 기간 종료가 각각 올바르게 안내됩니다. iOS 시연에는 카메라의 `높은 호환성` 설정을 안내합니다.

## 10. 랭킹과 그룹 화면

### 구현할 기능

개인·그룹 시즌 랭킹, 구성원·역할, 탈퇴·신고를 표시합니다.

### 주요 규칙

- 랭킹 동점 기준은 **도달 시각 → ID**, 탈퇴 회원은 랭킹에 `탈퇴`로 남기되 현 구성원에서는 제외합니다.
- 그룹 정원은 20명이며 주·부 관리자의 관리 화면 이동을 제공합니다.
- 유일한 주 관리자 탈퇴는 **그룹 비활성화·초대 링크·시즌 참여 종료**를 함께 확인합니다.
- 신고 대상은 본인을 제외한 그룹 구성원이며 사유는 10~1000자입니다. 신고만으로 자동 강퇴하지 않습니다.

### 완료 확인

탈퇴·신고 후 조회가 동기화되고, 역할별 버튼과 랭킹의 종료 회원 표기가 정확합니다.

## 11. 공통 조회·작업 상태

### 주요 규칙

| 상황 | 화면 처리 |
|---|---|
| 최초 조회 | Skeleton |
| 빈 목록 | 빈 사유와 다음 행동 표시 |
| 조회 오류 | 오류 코드·안내·재시도 |
| `429 RATE_LIMIT_EXCEEDED` | `Retry-After`까지 버튼 비활성화 |
| Gemini·사진 처리 포화 | 입력·선택 유지 후 사용자가 재시도 |
| WebSocket | 연결됨·재연결 중·30초 동기화 중 안내 및 REST 재조회 |
| 결제·탈퇴·강퇴·권한 변경 | 영향 범위 확인 Modal |

성공·실패 Toast는 일회성 피드백이며 영속 알림을 만들지 않습니다. Modal을 열었을 때의 조건을 신뢰하지 않고 제출 시 서버가 재검증합니다.

## 12. 그룹 관리 화면

### 구현할 기능

구성원·역할·미접속 강퇴·초대 링크·시즌 크리처 종을 관리합니다.

### 주요 규칙

| 주체 | 허용 동작 |
|---|---|
| 주 관리자 | 부 관리자 부여·회수, 72시간 미접속 일반·부 관리자 강퇴, 초대 재발급·주 관리자 이전 |
| 부 관리자 | 72시간 미접속 일반 회원 강퇴, 부화 기한 내 크리처 종 선택 |
| 주·부 관리자 | 그룹 시즌 참여 시작 후 72시간 안에 종 선택·변경 |

초대 Token 원문은 **그룹 생성·재발급 성공 직후만 복사**할 수 있고 분실하면 재발급해야 합니다. 주 관리자는 다른 활성 회원이 있으면 권한을 이전한 뒤 탈퇴하며, 이전 시 기존 주 관리자는 `MEMBER`가 됩니다. 강퇴 후 24시간 가입 제한을 확인 Modal에 표시합니다.

### 완료 확인

권한 없는 변경은 차단되고, 미접속 72시간 기준·부화 기한·링크 재발급의 즉시 무효화가 안내됩니다.

## 13. 상점·아바타·결제·환불 화면

### 구현할 기능

상점의 카카오페이 테스트 결제, 아바타 장착, 주문·환불 Timeline과 관리자 승인·거절을 표시합니다.

### 주요 규칙

- 카카오페이 테스트 채널만 제공하며 **PC IFrame·모바일 Redirect**를 사용합니다.
- 최초 장착 전 **사용 후 환불 불가**를 명시하고, 해제해도 미사용으로 되돌아가지 않습니다.
- 환불은 `PAID`·미사용·진행 중 환불 없음 조건에서만 요청합니다.
- PG 결과 불명은 성공·실패가 아닌 `확인 중`으로 표시합니다. `CANCEL_UNKNOWN`은 정합성 복구 대상으로 연결합니다.

### 완료 확인

결제·환불 불명 상태를 섣불리 성공으로 표시하지 않으며 환불 완료 아이템은 장착할 수 없습니다.

## 14. 가입·초대·그룹 선택 입력과 Feedback

### 주요 규칙

| 입력·동작 | 제약·피드백 |
|---|---|
| 가입 | 공급자·이메일 읽기 전용, 닉네임만 입력(정규화 후 2~20자) |
| 초대 | 그룹·인원·정원·수락·거절, 만료·무효 시 일반 가입 이동 |
| 그룹 선택 | 그룹명 검색·페이지 조회, 잔여석 표시·정원 마감 가입 비활성화 |
| 그룹 생성 | 이름 정규화 후 2~30자·전역 중복 금지, 소개 최대 500자 |
| Gemini | 명시적 클릭에만 교정, 원문과 제안 병렬 표시·선택적 적용, 실패해도 원문으로 생성 |
| 가입 제한 | 24시간 종료 시각·남은 시간 표시, 종료 뒤 서버 상태 재조회 |
| 중복 제출 | 처리 중 버튼 비활성화, `GROUP_FULL` 등 업무 오류 구분 |

### 완료 확인

입력 필드별 오류와 다음 행동이 표시되며 클라이언트 사전 검사만으로 서버 판정을 대체하지 않습니다.
