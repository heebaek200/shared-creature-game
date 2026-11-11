# 설계 결정 기록

프로젝트의 여러 영역에 영향을 주거나 기존 방향을 변경한 주요 결정만 기록합니다.

## 2026-11-02 다크 모드 MVP 필수 범위 확정

- 결정: 최초 프로젝트 요청을 기준으로 다크 모드를 선택 기능이 아닌 MVP 필수 기능으로 확정합니다.
- 상태: confirmed
- 근거: 통합 기획서의 선택 표기보다 현재 사용자의 명시적인 필수 기능 요청을 우선합니다.
- 영향 문서: requirements.md, project_plan.md, screen-design.md
- 대체한 결정: project_plan.md v0.2의 다크 모드 선택 범위

## 2026-11-02 공개 그룹과 공동 크리처 운영 단위 확정

- 결정: 사용자는 정원 내에서 공개 그룹에 승인 없이 직접 가입합니다. 그룹 정원은 20명이며 그룹마다 활성 크리처 1마리만 육성하고 개인 크리처는 MVP에서 제외합니다. 그룹 최초 생성자는 주 관리자가 되며 그룹을 생성할 수 있는 사용자 범위는 후속 결정합니다.
- 상태: confirmed
- 근거: 2주 MVP에서 공동 육성의 핵심을 유지하면서 그룹 생성과 크리처 소유 구조를 단순하고 명확하게 관리하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md
- 대체한 결정: 전체 관리자만 공개 그룹을 생성한다는 규칙

## 2026-11-02 관리자 대시보드 차트 기술 확정

- 결정: 관리자 대시보드의 통계 시각화에 Google Charts JavaScript 라이브러리를 사용합니다.
- 상태: confirmed
- 근거: 관리 통계를 웹 화면에서 차트로 표현하기 위한 프로젝트 기술 선택입니다.
- 영향 문서: screen-design.md, api-design.md, convention.md

## 2026-11-02 그룹 탈퇴와 재가입 제한 확정

- 결정: 사용자는 동시에 하나의 그룹에만 소속됩니다. 그룹을 탈퇴하면 서버가 탈퇴 시각을 기록하고, 해당 시각부터 24시간이 지나기 전에는 어떤 그룹에도 가입할 수 없습니다. 기존 그룹에 반영된 크리처 경험치와 그룹 점수는 유지하며 새 그룹으로 이전하지 않습니다.
- 상태: confirmed
- 근거: 잦은 그룹 이동을 통한 점수 이동과 그룹 간 기여 조작을 방지하면서 탈퇴 시점을 기준으로 제한 종료 시각을 명확하게 계산하기 위함입니다.
- 영향 문서: business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 회원가입·그룹 가입 통합 링크 확정

- 결정: 그룹 생성 시 회원가입과 그룹 가입을 함께 처리하는 사용 횟수 무제한 링크를 자동 생성합니다. 미가입자가 수락하면 계정 생성과 그룹 가입을 함께 완료하고, 거절하면 계정을 생성하지 않은 채 일반 회원가입 페이지로 이동합니다. 링크는 정원을 예약하지 않으며 수락 시 정원·기존 소속·탈퇴 후 24시간 제한을 다시 검사합니다.
- 상태: confirmed
- 근거: 미가입자를 특정 그룹의 회원가입 흐름으로 바로 안내하면서 그룹 없는 일반 사용자 계정 생성을 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 주 관리자 강퇴·부 관리자 관리 범위 확정

- 결정: 주 관리자도 마지막 접속 후 72시간이 지난 일반 사용자를 강퇴할 수 있습니다. 또한 주 관리자는 부 관리자의 접속 시각과 관계없이 권한을 회수하거나 그룹에서 강퇴할 수 있습니다. 부 관리자는 기존과 같이 72시간 미접속 일반 사용자만 강퇴할 수 있습니다.
- 상태: confirmed
- 근거: 주 관리자가 그룹 내 위임 권한과 구성원 상태를 최종 관리하되 부 관리자의 강퇴 권한은 일반 회원에 한정하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 특정 가입 사용자를 지정하는 초대 방식과 72시간 단일 초대 링크안

## 2026-11-02 탈퇴 회원의 기여 이력과 랭킹 처리 확정

- 결정: 탈퇴 후에도 개인 퀘스트·기여 이력을 보존합니다. 진행 중인 그룹 개인 랭킹에는 기존 기여도를 `탈퇴` 상태로 유지하되 추가 기여는 중단하고 현재 그룹 구성원 목록에서는 제외합니다.
- 상태: confirmed
- 근거: 기존 그룹 점수와 크리처 경험치의 산출 근거를 유지하면서 현재 구성원과 탈퇴 회원을 명확히 구분하기 위함입니다.
- 영향 문서: business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 전체 관리자·주 관리자·부 관리자·일반 사용자 역할 분리

- 결정: 전체 관리자는 여러 그룹을 관리합니다. 그룹 최초 생성자는 그룹당 1명인 주 관리자가 됩니다. 주 관리자는 같은 그룹의 일반 사용자에게 부 관리자 권한을 부여하거나 회수할 수 있습니다. 부 관리자는 여러 명을 둘 수 있고 일반 사용자 관리 권한을 가지지만 관리자 권한을 부여하거나 회수할 수 없습니다. 일반 사용자는 하나의 그룹에만 소속됩니다.
- 상태: confirmed
- 근거: 서비스 전체 운영 권한과 개별 그룹 운영 권한을 분리하고 여러 관리자가 한 그룹을 공동 운영할 수 있게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 자진 탈퇴·강퇴와 주 관리자 보호 확정

- 결정: 일반 사용자는 직접 탈퇴할 수 있습니다. 전체 관리자는 접수된 신고를 근거로 사용자를 그룹에서 강퇴할 수 있고 미접속 강퇴 기준은 마지막 접속 시각부터 72시간 이상입니다. 주 관리자는 대체자를 지정하기 전까지 권한 해제나 자진 탈퇴가 불가능하며 전체 관리자가 주 관리자를 재지정할 수 있습니다. 자진 탈퇴와 모든 강퇴에는 동일한 24시간 그룹 가입 제한을 적용합니다.
- 상태: confirmed
- 근거: 사용자 선택에 따른 탈퇴와 서비스·그룹 운영상 필요한 강제 퇴장을 서로 다른 권한과 조건으로 구분하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 회원가입과 그룹 선택 화면 분리

- 결정: 일반 회원가입을 완료하면 별도의 그룹 선택 페이지로 이동합니다. 그룹 선택 페이지는 신규 가입자와 탈퇴·강퇴 후 24시간 제한이 끝난 사용자가 공통으로 사용합니다.
- 상태: confirmed
- 근거: 계정 생성과 그룹 소속 결정을 별도 단계로 분리하면서 그룹이 없는 사용자가 그룹을 선택해야 하는 두 흐름을 같은 화면에서 처리하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, screen-design.md, api-design.md

## 2026-11-02 자동 생성 가입 링크의 수명 확정

- 결정: 그룹 가입 링크는 자동 만료되지 않고 사용 횟수 제한이 없습니다. 링크를 재발급하면 기존 링크는 즉시 무효화됩니다.
- 상태: confirmed
- 근거: 그룹이 지속적으로 공유할 수 있는 단일 링크를 제공하되 노출 문제가 생기면 교체할 수 있게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 부 관리자의 강퇴 범위 확정

- 결정: 부 관리자는 마지막 접속 시각부터 72시간 이상 지난 일반 사용자만 강퇴할 수 있으며 주 관리자와 다른 부 관리자를 강퇴할 수 없습니다. 신고 기반 강퇴는 전체 관리자만 처리합니다.
- 상태: confirmed
- 근거: 부 관리자에게 그룹 정리 권한을 제공하면서 상위·동급 관리자와 활동 중인 일반 사용자에 대한 권한 남용을 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 그룹 선택·생성과 초대 링크 흐름 확정

- 결정: 그룹이 없는 사용자는 그룹 선택 페이지에서 공개 그룹 가입 또는 새 그룹 생성을 선택할 수 있고 새 그룹 생성자는 주 관리자가 됩니다. 초대 링크를 수락하면 링크 정보를 유지한 회원가입 페이지로 이동하여 회원가입과 그룹 가입을 함께 완료하고, 거절하면 링크 정보를 버리고 일반 회원가입 페이지로 이동합니다. 링크 재발급은 주 관리자만 할 수 있습니다.
- 상태: confirmed
- 근거: 일반 가입, 그룹 생성과 링크 기반 가입의 화면 흐름 및 주 관리자 책임을 명확히 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, screen-design.md, api-design.md

## 2026-11-02 공동 크리처 4주 시즌제 확정

- 결정: `Asia/Seoul` 기준 첫 시즌은 서비스 개시 후 처음 도래하는 월요일 0시에 시작하고 이후 4주마다 크리처를 교체합니다. 시즌 첫 3일은 알 상태이며 주·부 관리자가 선택을 변경할 수 있습니다. 72시간 시점의 최종 선택으로 부화하고 선택이 없으면 무작위 부화합니다.
- 상태: confirmed
- 근거: 그룹 육성에 반복 가능한 주기를 만들고 시즌 초 선택 참여와 자동 진행을 함께 보장하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 일일·주간 퀘스트 갱신 시각 확정

- 결정: `Asia/Seoul` 기준 일일 퀘스트는 매일 0시, 주간 퀘스트는 매주 월요일 0시에 갱신합니다.
- 상태: confirmed
- 근거: 퀘스트 참여 기간과 집계 경계를 사용자에게 예측 가능하게 제공하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 시즌 종료·초기화 데이터 범위 확정

- 결정: 시즌 종료 시 기존 크리처 최종 상태와 시즌 랭킹을 보존합니다. 새 시즌에는 크리처 경험치, 시즌 기여도와 시즌 그룹 점수를 초기화하며 회원, 그룹, 아이템과 누적 활동 기록은 유지합니다.
- 상태: confirmed
- 근거: 시즌 경쟁과 육성 상태는 새로 시작하되 계정·소유 자산과 장기 활동 기록은 연속성을 유지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 초대 링크 가입 실패 시 계정 처리 확정

- 결정: 초대 링크 회원가입 제출 직전에 링크 유효성, 그룹 정원, 기존 소속과 탈퇴·강퇴 후 24시간 제한을 다시 검사합니다. 그룹 가입 조건을 충족하지 못해도 계정은 생성하고, 실패 이유를 표시한 뒤 그룹 선택 페이지로 이동합니다. 기존 가입자는 새 계정을 만들지 않고 현재 상태를 검사해 안내합니다.
- 상태: confirmed
- 근거: 가입 직전 발생할 수 있는 링크 재발급과 정원 경쟁으로 사용자의 회원가입 정보까지 잃지 않게 하면서 그룹 가입 규칙은 우회하지 않기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, screen-design.md, api-design.md
- 대체한 결정: 초대 링크 수락 시 계정 생성과 그룹 가입이 항상 함께 성공해야 한다는 규칙

## 2026-11-02 공동 크리처 성장 단계와 경험치 정책 확정

- 결정: 크리처는 `알 → 유년기 → 성장기 → 성숙기 → 완전체`로 성장합니다. 알은 시즌 첫 72시간의 시간 기반 상태이고, 부화 후에는 시즌 목표 경험치의 30%, 65%, 100%에서 성장기, 성숙기, 완전체가 됩니다. 유효한 일일·주간 퀘스트만 경험치를 지급하며 퀘스트별 크리처 경험치와 개인 기여도 점수는 분리합니다.
- 상태: confirmed
- 근거: 시간 기반 부화와 활동 기반 성장을 구분하고 완전체 도달 이후에도 기여도 경쟁이 계속되게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 완전체 이후 경험치 누적과 시즌 기록 확정

- 결정: 크리처 경험치는 완전체 기준을 넘어서도 제한 없이 누적하고 개인 기여도와 그룹 점수도 계속 누적합니다. 시즌 종료 시 실제 도달 단계와 경험치를 보존하며 같은 시즌 안에서는 성장 단계가 낮아지지 않습니다.
- 상태: confirmed
- 근거: 육성 목표 달성 후에도 퀘스트와 랭킹 참여 동기를 유지하면서 시즌 결과를 재현할 수 있게 하기 위함입니다.
- 영향 문서: business-rules.md, project_plan.md, database-design.md, api-design.md
- 대체한 결정: 크리처 경험치를 완전체 기준에서 상한 처리하는 규칙

## 2026-11-02 시즌 개인·그룹 랭킹 규칙 확정

- 결정: 개인 기여도는 퀘스트별 기여도 점수의 시즌 합계, 그룹 점수는 소속 사용자들의 시즌 기여도 합계로 계산합니다. 결제와 아이템 구매는 반영하지 않습니다. 새 시즌에 현재 점수를 초기화하고 종료된 시즌 랭킹은 조회 가능하게 보존합니다.
- 상태: confirmed
- 근거: 실제 퀘스트 참여만 경쟁에 반영하고 시즌별 경쟁 결과를 재현할 수 있게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 랭킹 동점 처리 확정

- 결정: 점수가 같으면 해당 점수에 먼저 도달한 사용자 또는 그룹을 상위로 정하고, 도달 시각도 같으면 사용자 ID 또는 그룹 ID 오름차순으로 순서를 고정합니다.
- 상태: confirmed
- 근거: 같은 점수에도 항상 재현 가능한 순서를 제공하고 먼저 성과를 달성한 대상을 우선하기 위함입니다.
- 영향 문서: business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 개발자 정의 퀘스트 순회 배정과 경험치 확정

- 결정: 시스템이 개발자가 정의한 순회 규칙에 따라 일일 3개와 주간 7개를 선정해 모든 사용자에게 동일하게 배정합니다. 관리자 역할은 목록을 수동 선택하지 않습니다. 각 퀘스트는 기간당 한 번만 완료하고 이월하지 않습니다. 일일 퀘스트는 크리처 경험치 100, 주간 퀘스트는 300을 지급하여 사용자 1명의 주간 최대 경험치는 4,200입니다.
- 상태: confirmed
- 근거: 그룹 간 동일한 퀘스트 조건과 예측 가능한 순회를 제공하고 운영자 선택에 따른 경쟁 조건 차이를 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md
- 대체한 결정: 시스템 무작위 선정 방식

## 2026-11-02 알 상태 경험치 누적 확정

- 결정: 시즌 첫 3일의 알 상태에서도 퀘스트 경험치와 개인 기여도를 누적하고, 부화 시 누적 경험치를 적용해 도달한 가장 높은 성장 단계로 즉시 전환합니다.
- 상태: confirmed
- 근거: 시즌 초 3일의 활동이 성장에서 누락되지 않게 하고 부화 시점에 그룹 활동 결과를 즉시 반영하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 크리처 성장 경험치 절대값 확정

- 결정: 부화 후 유년기는 0부터 시작하고 그룹 누적 경험치 45,000에서 성장기, 97,500에서 성숙기, 150,000에서 완전체가 됩니다. 완전체 이후에도 경험치는 제한 없이 누적합니다.
- 상태: confirmed
- 근거: 20명 그룹의 이론상 시즌 최대 336,000 중 약 9명이 모든 퀘스트를 완료하면 완전체에 도달하도록 4주 참여 난이도를 설정하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md
- 대체한 결정: 시즌 목표 경험치 비율만 기록한 성장 임계값

## 2026-11-02 퀘스트 난이도별 기여도 확정

- 결정: 난이도는 크리처 경험치에 영향을 주지 않고 개인 기여도에만 반영합니다. 일일 퀘스트는 하 10·중 20·상 30, 주간 퀘스트는 하 30·중 60·상 90을 지급합니다.
- 상태: confirmed
- 근거: 사용자 1명의 주간 최대 크리처 경험치 4,200은 유지하면서 난이도가 높은 퀘스트를 개인·그룹 랭킹에서 더 크게 평가하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 퀘스트 난이도 구성·중복·부족 처리 확정

- 결정: 일일 퀘스트는 하·중·상 각 1개, 주간 퀘스트는 하 3개·중 2개·상 2개로 구성합니다. 같은 기간에는 동일 템플릿을 중복 배정하지 않고 다음 기간에는 순회 규칙에 따라 다시 사용할 수 있습니다. 필요한 템플릿이 부족하면 배정을 실패 처리하고 전체 관리자에게 부족한 난이도와 개수를 알립니다.
- 상태: confirmed
- 근거: 난이도 구성을 일정하게 유지하고 중복 과제를 방지하며 잘못된 템플릿 구성으로 불완전한 퀘스트 세트가 배포되지 않게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, api-design.md

## 2026-11-02 아바타 아이템 사용과 환불 흐름 확정

- 결정: 결제 한 건당 아바타 아이템 하나를 구매하고 최초 장착 시 사용된 것으로 확정합니다. 사용자는 환불을 요청하고 전체 관리자가 승인·거절하며, 미사용·중복 환불 여부를 재검사한 뒤 PG 취소 성공 시 아이템을 회수합니다.
- 상태: confirmed
- 근거: 아이템 사용 기준을 사용자 행동으로 명확하게 정의하고 테스트 결제 취소와 관리자 운영 흐름을 함께 검증하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, database-design.md, screen-design.md, api-design.md

## 2026-11-02 주요 쓰기 작업의 동시성 제어 확정

- 결정: 퀘스트 보상, 그룹 가입, 아이템 장착·환불과 시즌·퀘스트 스케줄에 UNIQUE 제약, 고정 순서 행 잠금, 상태 조건부 갱신과 멱등 키를 적용합니다. 외부 PG 호출은 DB 잠금 트랜잭션 밖에서 수행하고 상태 선점과 결과 확정을 별도 트랜잭션으로 나눕니다.
- 상태: confirmed
- 근거: 중복 보상, 경험치 Lost Update, 그룹 정원 초과, 장착·환불 경합과 중복 PG 취소를 방지하면서 외부 호출로 DB 잠금이 장시간 유지되지 않게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, project_plan.md, api-design.md, convention.md

## 2026-11-02 카카오·구글 전용 소셜 인증 확정

- 결정: 회원가입과 로그인은 카카오·구글 소셜 로그인만 지원하고 자체 이메일·비밀번호 인증과 비밀번호 저장은 제공하지 않습니다.
- 상태: superseded
- 근거: 외부 서버 연동 요구사항을 충족하면서 자체 자격 증명 관리 범위와 보안 부담을 줄이기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, project_plan.md, api-design.md, convention.md

## 2026-11-02 구글·네이버 전용 소셜 인증으로 변경

- 결정: 카카오 로그인을 제외하고 회원가입과 로그인 공급자를 구글·네이버로 변경합니다. 네이버는 개발 단계에서 팀원 4명을 관리자·테스터로 등록해 시연하며, 불특정 사용자를 대상으로 공개 운영하기 전 사전 검수를 통과해야 합니다.
- 상태: confirmed
- 근거: 카카오 이메일 동의항목을 사용하기 위한 비즈 앱 전환 부담을 피하면서 신규 가입에 필요한 이메일을 확보하고, 네이버의 검수 전 관리자·테스터 로그인 범위로 4인 팀의 구현과 시연을 완료하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, project_plan.md, api-design.md, convention.md, common-foundation.yaml
- 대체한 결정: 카카오·구글 전용 소셜 인증

## 2026-11-02 Full CSR 구조 확정

- 결정: 화면은 Full CSR로 구성하고 Spring Boot 백엔드는 REST API와 OAuth 콜백을 제공합니다.
- 상태: confirmed
- 근거: 클라이언트 상태와 화면 전환을 프론트엔드에서 일관되게 관리하고 백엔드의 역할을 인증·업무 API에 집중하기 위함입니다.
- 영향 문서: requirements.md, screen-design.md, project_plan.md, api-design.md, convention.md

## 2026-11-02 소셜 계정 자동 연결 금지 확정

- 결정: 회원은 소셜 공급자와 공급자 사용자 식별자의 조합으로 식별합니다. 동일 이메일의 다른 공급자 계정을 자동 연결하지 않고 기존 가입 공급자로 로그인하도록 안내합니다.
- 상태: confirmed
- 근거: 이메일 일치만으로 서로 다른 소셜 계정을 합치는 오연결 위험을 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, database-design.md, api-design.md

## 2026-11-02 Full CSR 서버 세션 인증 확정

- 결정: OAuth Authorization Code는 백엔드 콜백에서 서버 간 교환하고 애플리케이션 인증은 Spring Session JDBC 기반 서버 세션으로 유지합니다. 세션 쿠키에는 `HttpOnly`, `Secure`, `SameSite=Lax`를 적용하고 상태 변경 API에는 CSRF 검증을 적용합니다.
- 상태: confirmed
- 근거: Full CSR 화면 구조를 유지하면서 공급자 토큰과 장기 인증 수단이 브라우저 JavaScript에 노출되는 범위를 줄이고 강퇴·권한 변경을 즉시 반영하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, convention.md, project_plan.md

## 2026-11-02 세션 수와 소셜 이메일 정책 확정

- 결정: 세션은 7일 비활성 만료로 운영하고 사용자당 최대 3개를 허용하며 초과 시 가장 오래된 세션을 종료합니다. 신규 가입은 공급자 이메일을 필수로 요구하고 기존 회원은 공급자 식별자가 일치하면 이후 이메일 제공 여부와 관계없이 로그인합니다.
- 상태: confirmed
- 근거: 게임 서비스의 재로그인 부담을 줄이면서 장기간 방치된 세션과 무제한 동시 접속을 제한하고, 신규 회원의 동일 이메일 공급자 충돌을 판정하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, api-design.md, convention.md, project_plan.md

## 2026-11-02 결제·아이템·환불 상태머신 확정

- 결정: 결제는 `READY`, `PAID`, `FAILED`, `CANCELLED`, 아이템은 `NOT_OWNED`, `OWNED_UNUSED`, `OWNED_USED`, `REVOKED`, 환불은 `REQUESTED`, `REJECTED`, `CANCEL_PENDING`, `CANCEL_UNKNOWN`, `COMPLETED`, `FAILED` 상태로 관리합니다. PG 취소 결과를 알 수 없으면 `CANCEL_UNKNOWN`에서 장착과 중복 취소를 차단하고 PG 조회 결과로만 다음 상태를 결정합니다.
- 상태: confirmed
- 근거: 외부 PG의 Timeout이나 통신 단절을 취소 실패로 단정해 중복 취소를 발생시키거나, 취소된 결제의 아이템이 남는 정합성 문제를 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 퀘스트 카탈로그 규모와 순회 공식 확정

- 결정: 일일 활성 템플릿은 하 5·중 4·상 3개, 주간 활성 템플릿은 하 6·중 4·상 4개 이상으로 구성합니다. 첫 시즌 시작부터의 KST 누적 일수·주차에 난이도별 오프셋 하 0·중 1·상 2를 더해 원형 순회하고 시즌이 바뀌어도 인덱스를 초기화하지 않습니다. 배정 시 템플릿 버전과 판정·보상 데이터를 Snapshot으로 보존합니다.
- 상태: confirmed
- 근거: 모든 사용자에게 재현 가능한 동일 배정을 제공하면서 4주 시즌 안에서 조합 변화를 확보하고 템플릿 수정이 진행 중·과거 퀘스트의 판정과 보상을 바꾸지 않게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 퀘스트 혼합 검증 모델 확정

- 결정: 퀘스트 판정은 `SELF_CHECK`, `SERVER_EVENT`, `AGGREGATE`, `PHOTO_EXIF` 네 유형으로 구분합니다. 생활 활동은 자기 확인, 서비스 행동은 서버 이벤트, 주간 누적은 서버 집계를 사용하고 사진 인증은 기술 검증 전까지 `EXPERIMENTAL`로 순회에서 제외합니다. 관리자 수동 승인과 완료 취소는 MVP에서 제공하지 않습니다.
- 상태: confirmed
- 근거: 2주 MVP에서 생활 활동의 다양한 퀘스트를 제공하면서 서버가 확인 가능한 데이터는 신뢰 경계 안에서 자동 판정하고, EXIF 기술 위험이 전체 배정을 막지 않게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 일일·주간 퀘스트 템플릿 확정

- 결정: 일일 활성 템플릿 12개와 주간 활성 템플릿 14개를 생활 자기 확인, 명시적 서비스 체크인과 서버 집계 조건으로 구성합니다. 서비스 체크인은 사용자당 KST 날짜별 1회이고 05:00 이상 09:00 미만을 오전 기상 체크인으로 봅니다. 위치 사진 일일·주간 템플릿은 `EXPERIMENTAL`로 별도 유지합니다.
- 상태: confirmed
- 근거: 확정된 난이도별 최소 카탈로그 수를 충족하면서 2주 MVP에서 구현 가능한 공통 검증 유형을 재사용하고, 단순 페이지 조회를 참여 행동으로 오인하지 않게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, common-foundation.yaml

## 2026-11-02 데이터베이스 40개 업무 테이블 구성 확정

- 결정: 계정·인증 4개, 그룹·운영 7개, 시즌·크리처·랭킹 9개, 퀘스트·활동 9개, 결제·아바타 8개, 운영 공통 3개의 총 40개 업무 테이블로 설계합니다. EXIF 구현을 보류하면 `photo_submissions`를 제외한 39개를 구현하며 Spring Session과 마이그레이션 테이블은 업무 테이블 집계에서 제외합니다.
- 상태: superseded
- 근거: 각 테이블이 독립된 업무 상태·이력·정합성 책임을 갖게 하면서 요구된 30~40개 범위를 의미 없는 분리 없이 충족하기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 알림 기능 보류와 39개 업무 테이블로 조정

- 결정: 사용자·관리자 알림 기능과 `notifications` 테이블은 프로젝트 이후로 보류합니다. 프로젝트 기간에는 운영 이상을 관리자 대시보드 경고 목록으로 제공하며 업무 테이블은 39개, EXIF 보류 시 38개를 구현합니다.
- 상태: confirmed
- 근거: 2주 프로젝트에서 핵심 게임·동시성·결제 흐름에 집중하고 별도 알림 전달·읽음 상태 기능을 현재 범위에서 제외하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 40개 업무 테이블에 `notifications`를 포함하는 구성

## 2026-11-02 OAuth 로그인 시도 유효·보존 기간 확정

- 결정: `oauth_login_attempts`는 생성 후 10분 동안 유효하고 `CONSUMED`, `EXPIRED`, `FAILED` 종료 상태 전환 후 24시간 보존한 뒤 물리 삭제합니다. 해당 삭제는 회원 계정, 소셜 계정 연결과 Spring Session에 영향을 주지 않습니다.
- 상태: confirmed
- 근거: OAuth state 재사용을 차단하고 중복 콜백·실패 원인을 단기간 확인할 수 있게 하면서 장기 가치가 없는 임시 보안 문맥을 불필요하게 보관하지 않기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 운영 테이블·보존 정책 확정

- 결정: `scheduler_executions`는 `(job_type, scheduled_for)` UNIQUE와 만료 가능한 Lease로 논리 작업당 실행권을 한 건만 허용하고 같은 행에 재시도 횟수와 결과를 누적합니다. 성공 기록은 90일, 실패 기록과 Append-only `admin_audit_logs`는 1년 보관 후 배치 삭제합니다. 결제·환불·PG 정합성 기록은 프로젝트 범위에서 자동 삭제하지 않습니다. 운영 경고는 별도 알림 테이블 없이 원본 오류 상태를 관리자 대시보드에서 조회합니다.
- 상태: confirmed
- 근거: 다중 인스턴스 Scheduler의 중복 실행과 장애 인계를 제어하고, 프로젝트에 필요한 진단·감사 기간을 확보하면서 무기한 운영 로그 축적을 피하기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 EXIF 인증 조건부 구현·보존 정책 확정

- 결정: `PHOTO_EXIF`는 Android Chrome과 iOS Safari 실기기에서 GPS·촬영 시각 추출을 모두 안정적으로 확인한 경우에만 MVP에 포함합니다. 한 환경이라도 검증 기준을 충족하지 못하면 사진 퀘스트와 `photo_submissions`를 제외합니다. 1시간을 넘긴 `PENDING` 제출은 실패 처리하고, 실패 파일·좌표는 판정 후 24시간 이내, 성공 파일·좌표는 시즌 종료 후 30일에 삭제합니다. 판정 상태와 최소 활동 이력은 유지합니다.
- 상태: confirmed
- 근거: 모바일 브라우저·파일 처리 환경 차이로 핵심 게임 흐름이 지연되는 것을 막고, GPS 위치정보와 사진 파일의 보관을 검증·분쟁 확인에 필요한 기간으로 제한하기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 CSR 경로·접근 제어 확정

- 결정: 로그인·초대·가입·그룹 선택, 일반 기능, 그룹 관리와 전체 관리 화면을 고유 CSR 경로로 분리합니다. `/api/v1/me`의 계정·온보딩·그룹·역할 상태로 Route Guard와 기본 이동을 결정하되 실제 권한은 서버 API가 재검증합니다. 24시간 제한 중인 그룹 없는 계정은 그룹 선택 화면을 볼 수 있지만 가입·생성 동작은 사용할 수 없습니다. 권한 부족과 미등록 경로는 각각 403·404 화면으로 처리합니다.
- 상태: confirmed
- 근거: 새로고침·직접 URL 접근에서도 사용자 상태에 맞는 화면을 복원하고 프론트엔드 접근 제어를 서버 권한 검증으로 오인하지 않도록 경계를 명확히 하기 위함입니다.
- 영향 문서: screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 공통 레이아웃·색상·다크 모드 확정

- 결정: 데스크톱은 좌측 사이드바, 모바일은 하단 5개 내비게이션을 사용하고 전체 관리 영역은 별도 사이드바로 분리합니다. 자연·성장·편안함을 디자인 방향으로 메인 `#16836F`, 보조 `#A8DBC1`, 강조 `#FFD17B`, 라이트 배경 `#F3FAF6`를 사용합니다. 테마는 시스템·라이트·다크 세 가지를 제공하고 Google Charts와 접근성 상태도 현재 테마에 맞춰 갱신합니다.
- 상태: confirmed
- 근거: 크리처 성장·건강·돌봄의 인상을 일관된 색상과 반응형 구조로 전달하고, 모바일·데스크톱과 명암 환경에서 핵심 기능을 동일하게 사용할 수 있도록 하기 위함입니다.
- 영향 문서: requirements.md, screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 핵심 사용자 화면·공통 UI 상태 확정

- 결정: 크리처 홈은 성장·경험치·시즌 시간과 실시간 기여를, 퀘스트는 배정·보상·검증·처리 상태를, 랭킹과 그룹 화면은 현재 순위·탈퇴 상태·구성원 역할을 표시합니다. 공통 조회에는 Skeleton, 빈 상태, 오류 재시도를 제공하고 WebSocket 재연결 후 REST로 정합성을 회복합니다. Toast는 영속 알림이 아닌 현재 작업 결과에만 사용하고 중요한 상태 변경은 확인 Modal 제출 시 서버가 다시 검증합니다.
- 상태: confirmed
- 근거: 사용자가 공동 육성의 현재 상태와 자신의 다음 행동을 즉시 파악하고, 지연·중복 요청·실시간 연결 장애에서도 서버 상태와 일치하는 결과를 확인할 수 있도록 하기 위함입니다.
- 영향 문서: screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 전체 관리자 대시보드 지표·차트·갱신 확정

- 결정: 전체 관리자 대시보드는 활동 사용자, 그룹 정원, 시즌 퀘스트 완료, 결제·환불, 신고와 운영 경고를 요약합니다. Google Charts의 Line, Column, Bar, Combo, Donut Chart를 사용하고 KST 7일·30일·현재 시즌 및 그룹 필터를 제공합니다. 백엔드 집계 결과를 진입 즉시 조회하고 표시 중 60초마다 갱신하며 차트별 표 대체 정보와 마지막 집계 시각을 제공합니다.
- 상태: confirmed
- 근거: 전체 관리자가 여러 그룹과 핵심 운영 이상을 한 화면에서 비교하되 브라우저 대량 재집계와 차트 라이브러리 실패가 관리 업무를 막지 않도록 하기 위함입니다.
- 영향 문서: screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 그룹 관리·상점·환불 화면 흐름 확정

- 결정: 그룹 관리 화면은 역할·마지막 접속 조건에 따라 가능한 권한·강퇴·초대·크리처 작업만 제공합니다. 상점·아바타·결제 화면은 최초 사용 후 환불 불가, PG 결과 불명과 환불 상태를 Timeline과 명시적 상태로 표시하며 변경 조건은 제출 시 서버가 다시 검증합니다.
- 상태: confirmed
- 근거: 권한이 없는 위험 작업과 결제 중복 요청을 UI에서 줄이면서 UI 표시가 오래된 경우에도 서버의 최신 상태로 업무 규칙을 보장하기 위함입니다.
- 영향 문서: screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 가입·초대·그룹 선택 입력과 오류 표시 확정

- 결정: 회원가입은 소셜 공급자·이메일을 읽기 전용으로 확인하고 닉네임만 입력합니다. 초대 화면은 그룹 정보와 수락·거절을, 그룹 선택은 검색·페이지 조회·가입·생성을 제공합니다. 닉네임과 그룹 이름은 정규화 후 길이·중복을 검사하고 정원·소속·24시간 제한·링크 유효성은 제출 시 서버가 최종 재검사합니다. 실패는 사용자가 다음 행동을 판단할 수 있는 업무 사유로 표시합니다.
- 상태: confirmed
- 근거: 소셜 로그인 전용 정책에 불필요한 자격 입력을 제거하고 동시 가입과 오래된 화면 정보로 발생하는 실패를 명확하게 복구할 수 있도록 하기 위함입니다.
- 영향 문서: screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 공통 REST 응답·CSRF·멱등성 계약 확정

- 결정: 업무 API는 `/api/v1` 아래에서 `data`·`meta` 성공 응답과 안정적인 업무 코드 기반 오류 응답을 사용합니다. CSRF Token은 전용 API에서 받아 메모리에만 보관하고 외부 효과가 있는 결제·환불 명령에는 `Idempotency-Key`를 요구합니다. 모든 응답은 `X-Request-Id`로 추적하며 시각은 UTC ISO 8601로 전달합니다.
- 상태: confirmed
- 근거: Full CSR이 세션·CSRF를 안전하게 사용하고 네트워크 재시도와 외부 PG 호출에서도 중복 효과를 막으며 화면이 일관된 방식으로 오류를 처리하도록 하기 위함입니다.
- 영향 문서: api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 화면별 와이어프레임과 문서 링크 확정

- 결정: 모든 확정 CSR 경로의 데스크톱·모바일 또는 반응형 핵심 구조를 `docs/design/wireframes/`의 화면별 Markdown 파일로 관리하고 `screen-design.md` 경로표에서 직접 연결합니다.
- 상태: confirmed
- 근거: 화면 요구사항과 실제 구현 대상 레이아웃을 화면 단위로 추적하고 팀원이 경로·권한·상태를 설계 문서에서 즉시 확인할 수 있도록 하기 위함입니다.
- 영향 문서: screen-design.md, docs/design/wireframes/*.md, project_plan.md, common-foundation.yaml

## 2026-11-02 인증·온보딩 API Endpoint 확정

- 결정: CSRF, OAuth 시도 생성과 Callback, 가입 문맥·완료, `/api/v1/me`, 로그아웃 및 초대 조회·수락·거절 Endpoint를 분리합니다. Callback은 서버 간 코드 교환 후 민감정보 없는 CSR 경로로 Redirect하고 초대 수락은 최종 가입 전까지 자리를 예약하지 않습니다.
- 상태: confirmed
- 근거: Full CSR과 서버 세션 경계를 명확히 하고 초대 가입 문맥을 브라우저 저장소나 Callback URL에 노출하지 않으면서 사용자 상태별 Route Guard를 구성하기 위함입니다.
- 영향 문서: api-design.md, screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 크리처 홈 그룹 정원 아바타 과시 확정

- 결정: 크리처 홈 중앙의 공동 크리처 주변에 현재 접속한 그룹 구성원의 장착 아바타를 최대 20명 표시합니다. 아바타 선택 시 닉네임, 시즌 기여·순위와 장착 상품을 보여주고 판매 중인 상품은 상점으로 연결합니다. WebSocket은 접속·퇴장·장착 변경만 전달하고 이동 애니메이션은 클라이언트가 담당합니다. MVP Presence는 영구 저장 없이 단일 인스턴스 메모리로 운영합니다.
- 상태: confirmed
- 근거: 주 수입원인 아바타 아이템의 장착 결과를 그룹 안에서 자연스럽게 발견·비교하게 하면서 위치 동기화 패킷, 충돌 처리와 별도 Presence 테이블 없이 2주 MVP 범위에서 실시간 과시 경험을 제공하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, api-design.md, project_plan.md, docs/design/wireframes/creature.md, common-foundation.yaml

## 2026-11-02 WebSocket 목적지·Snapshot 동기화·상태 위치 확정

- 결정: `/ws`의 STOMP 그룹 채널에서 Presence, 장착, 크리처와 기여 이벤트를 전파합니다. 클라이언트는 구독 후 REST Snapshot을 적용하고 Stream 식별자 변경·Sequence 누락·재연결 때 Snapshot을 다시 조회하며 연결 실패 중에는 30초 Polling을 사용합니다. 연결 상태는 크리처 콘텐츠 영역이 아니라 헤더의 프로필 바로 앞에 표시합니다.
- 상태: confirmed
- 근거: REST와 WebSocket 사이의 초기 조회 경합과 이벤트 누락을 복구하고, 실시간 연결 상태를 모든 화면에서 일관되게 확인하게 하기 위함입니다.
- 영향 문서: api-design.md, screen-design.md, docs/design/wireframes/creature.md, project_plan.md, common-foundation.yaml

## 2026-11-02 그룹·시즌·퀘스트·랭킹 API 확정

- 결정: 공개 그룹 검색·생성·가입, 현재 그룹 구성원·권한·강퇴·주 관리자 이전·초대 링크, 시즌·크리처 홈·종 선택, 퀘스트·체크인·조건부 사진 제출과 시즌 랭킹 Endpoint를 `/api/v1` 아래에 구성합니다. 명령은 화면 표시와 관계없이 최신 소속·권한·정원·기한을 다시 검사합니다.
- 상태: confirmed
- 근거: 사용자·그룹·시즌 경계를 URL에 명확히 드러내고 동시 가입·권한 변경·중복 보상에서 서버 업무 규칙을 일관되게 적용하기 위함입니다.
- 영향 문서: api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 포트원 V2 결제 공급자·확장 경계 확정

- 결정: 테스트 결제 공급자는 포트원 V2로 확정합니다. `PaymentGateway` Port와 `PortOnePaymentGateway` Adapter를 분리하고 브라우저 결제 결과 대신 서버의 포트원 단건 조회로 상태·Store·금액·통화를 검증합니다. Webhook은 2024-04-25 형식의 Signature를 검증한 뒤 최신 결제를 조회하며 환불은 포트원 V2 전체 취소 API와 애플리케이션·공급자 양쪽 멱등 키를 사용합니다.
- 상태: confirmed
- 근거: 현재 공급자 연동을 구체화하면서 구매·환불 도메인이 포트원 DTO와 오류에 종속되는 것을 막고 브라우저·Webhook 도착 순서와 네트워크 Timeout에도 중복 지급·취소를 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 명령·응답 DTO와 업무 오류 카탈로그 확정

- 결정: 주요 명령 DTO는 업무 처리에 필요한 식별자·사유·명시적 확인만 받고 보상·금액 합계 같은 서버 계산값을 받지 않습니다. 응답은 Entity와 포트원 DTO를 노출하지 않고 화면 단위 Snapshot으로 구성합니다. 인증·그룹·시즌·아바타·결제·환불·동시성 오류 코드는 Endpoint와 관계없이 고정된 HTTP 상태와 의미를 사용합니다.
- 상태: confirmed
- 근거: 클라이언트 위변조 입력과 Persistence·외부 공급자 결합을 줄이고 여러 화면이 같은 업무 실패를 일관된 방식으로 복구하도록 하기 위함입니다.
- 영향 문서: api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 Vanilla JS·Bootstrap v5와 라우팅 구현 단계 확정

- 결정: Full CSR 프론트엔드는 Vite 기반 Vanilla JavaScript ES Module로 구성하고 UI·반응형 CSS Framework는 Bootstrap v5.3 계열을 npm Package로 사용합니다. 기본 History API Router와 Route Guard의 실제 구현은 아말라이트가 아닌 스피어 프로토콜의 공통 기반 구현 범위에 포함합니다.
- 상태: confirmed
- 근거: 팀의 JavaScript 학습 범위를 유지하면서 Bootstrap의 반응형 Component와 v5.3 색상 모드를 활용하고, 모든 기능이 공통으로 필요로 하는 라우팅 기반은 스피어에서 한 번에 검증하기 위함입니다.
- 영향 문서: requirements.md, screen-design.md, convention.md, project_plan.md, common-foundation.yaml

## 2026-11-02 Vanilla JS 모듈·Page 생명주기 확정

- 결정: 프론트엔드는 Route 화면 `pages`, 업무 `features`, 공통 DOM `components`, 통신 `api`와 STOMP·포트원·Google Charts 외부 경계를 분리합니다. 각 Page는 `mountPage(context)`를 Export하고 화면 이동 시 Event, Timer, 요청, WebSocket과 Bootstrap Instance를 정리하는 Cleanup 함수를 반환합니다.
- 상태: confirmed
- 근거: UI Framework가 제공하는 Component 생명주기가 없는 Vanilla JS에서 화면 이동 후 Listener·Timer·연결이 중복되는 문제를 막고 팀원이 기능 단위로 병렬 작업할 수 있도록 하기 위함입니다.
- 영향 문서: convention.md, project_plan.md, common-foundation.yaml

## 2026-11-02 백엔드 패키지·계층·트랜잭션과 DTO 자율성 확정

- 결정: 기능 중심 패키지를 기본으로 하고 그룹·퀘스트·결제처럼 복잡한 기능만 `api`, `application`, `domain`, `infrastructure`로 분리합니다. 외부 포트원 호출은 처리권 선점과 결과 확정 트랜잭션 사이에서 수행하고 WebSocket은 Commit 이후 발행합니다. DTO는 `class`·`record`, 입력 검증은 Bean Validation·`validate()`를 팀원이 자율 선택합니다.
- 상태: confirmed
- 근거: 2주 프로젝트에서 불필요한 Interface와 계층을 줄이면서 동시성·외부 결제 경계는 명확히 하고, DTO 문법과 검증 도구보다 검증 책임의 위치와 결과 일관성을 우선하기 위함입니다.
- 영향 문서: convention.md, project_plan.md, common-foundation.yaml

## 2026-11-02 초심자 중심 프론트엔드와 단순 테스트 정책 확정

- 결정: Vanilla JavaScript는 Router, API, DOM·Event, Cleanup의 네 단계로 설명하고 목 모드를 통해 백엔드 없이도 브라우저에서 화면을 확인할 수 있게 합니다. 프론트엔드 자동 테스트 Framework와 Docker·Testcontainers는 사용하지 않으며, 백엔드는 JUnit 5와 로컬 MySQL 테스트 DB로 핵심 업무·동시성만 자동 검증합니다. SQL 포매팅은 팀원 자율로 둡니다.
- 상태: confirmed
- 근거: 4명·2주 프로젝트에서 팀원이 이해하고 직접 실행할 수 있는 검증 방법을 우선하기 위함입니다.
- 영향 문서: convention.md, project_plan.md, common-foundation.yaml

## 2026-11-02 클래스 다이어그램과 구현 책임 경계 확정

- 결정: 클래스 다이어그램을 인증·그룹, 시즌·퀘스트·랭킹, 아바타·결제·환불, 공통 기반·실시간·동시성의 네 영역으로 나눕니다. DTO와 단순 메서드는 생략하고 Controller·Service·Repository·Entity 및 실제 교체가 필요한 외부 Port·Adapter의 책임과 핵심 잠금 순서를 표시합니다.
- 상태: confirmed
- 근거: 39개 업무 테이블을 한 그림에 나열하지 않고 각 팀원이 자신의 기능 흐름, 트랜잭션과 외부 연동 경계를 빠르게 찾을 수 있게 하기 위함입니다.
- 영향 문서: class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 ERD·요청 추적성·API 필드 계약 보완 확정

- 결정: 39개 업무 테이블을 모두 포함하는 영역별 Mermaid ERD 5개를 추가합니다. 사용자 시나리오에는 변경하지 않는 요청 ID 32개를 부여하고 화면 경로와 API Operation에서 같은 ID를 참조합니다. 56개 REST Endpoint에는 Operation ID와 입력 위치·DTO·성공 응답을 연결하고 요청·응답 필드의 타입, 필수 여부, 길이, 허용값과 Null 가능 여부를 상세 정의합니다.
- 상태: confirmed
- 근거: 업무 규칙에서 화면·API·데이터 모델까지 구현 범위를 역추적하고 팀원이 DTO와 검증 조건을 추측하지 않게 하기 위함입니다.
- 영향 문서: business-rules.md, database-design.md, screen-design.md, api-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 DB 공통 컬럼·시간·생명주기 정책 확정

- 결정: 기본키는 `BIGINT AUTO_INCREMENT id`, 외래키는 `{대상}_id`, 상태는 문자열 Enum, 금액·경험치·점수는 `BIGINT`, 위·경도는 `DECIMAL(10, 7)`을 사용합니다. 시각은 UTC `DATETIME(6)`으로 저장하고 KST 업무 기준일은 필요 시 `DATE`로 별도 저장합니다. 이력·원장·결제 데이터는 물리 삭제하지 않으며 `version`과 JSON은 필요한 테이블에만 선택 적용합니다.
- 상태: confirmed
- 근거: 자료형과 시간 해석을 일관되게 유지하면서 모든 테이블에 불필요한 공통 컬럼을 추가하지 않고 이력 재현성과 조회·제약 성능을 보장하기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, convention.md, common-foundation.yaml

## 2026-11-02 활성 행 UNIQUE와 그룹 정원 카운터 확정

- 결정: 활성 멤버십, 진행 중 환불과 활성 초대 링크는 상태 조건 STORED 생성 컬럼과 UNIQUE 인덱스로 한 건만 허용합니다. 그룹 정원은 `current_member_count` 조건부 증가와 멤버십 생성을 같은 트랜잭션에서 처리하고 불일치 점검은 자동 수정 없이 알림·감사·명시적 복구로 처리합니다.
- 상태: confirmed
- 근거: MySQL에서 과거 상태 이력을 여러 건 보존하면서 활성 행의 유일성을 DB가 강제하고, 동시 가입에서도 정원 20명 초과와 카운터 유실을 방지하기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 관계 Cardinality와 FK 삭제 정책 확정

- 결정: 소셜 계정·프로필은 사용자와 1:1, 그 외 그룹·시즌·퀘스트·결제 이력은 기준 Aggregate와 1:N으로 보존합니다. 핵심 업무 FK는 `ON DELETE RESTRICT`를 기본으로 하고 Cascade 삭제를 사용하지 않습니다. 그룹당 활성 주 관리자 멤버십은 `primary_group_id` 생성 컬럼 UNIQUE로 한 명만 허용합니다.
- 상태: confirmed
- 근거: 계정 자동 연결을 금지한 MVP 관계를 명확히 하고 부모 상태 종료나 실수로 과거 활동·랭킹·결제 이력이 연쇄 삭제되는 것을 방지하기 위함입니다.
- 영향 문서: requirements.md, database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 계정·그룹 식별자와 상세 컬럼 확정

- 결정: 닉네임은 전역 2~20자, 그룹 이름은 전역 2~30자로 중복을 금지하고 정규화 값에 UNIQUE를 적용합니다. 그룹 이름은 MVP에서 변경하지 않으며 소셜 이메일은 가입 당시 Snapshot으로 보존합니다. OAuth state와 초대 링크 토큰은 SHA-256 Hash만 저장합니다. 계정·그룹 11개 테이블의 컬럼과 인덱스는 데이터베이스 설계서 명세를 따릅니다.
- 상태: confirmed
- 근거: 표시값의 공백·대소문자·Unicode 차이를 이용한 중복 우회를 막고 URL에 노출되는 일회용 토큰 원문 유출 위험을 줄이면서 계정·그룹 조회와 이력 보존에 필요한 인덱스를 명확히 하기 위함입니다.
- 영향 문서: database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 시즌·크리처·랭킹 상세 컬럼 확정

- 결정: 시즌·크리처·랭킹 9개 테이블의 컬럼, 상태, UNIQUE와 조회 인덱스를 데이터베이스 설계서와 같이 구성합니다. 그룹 크리처는 시즌 시작 시 알 상태로 생성해 경험치를 즉시 누적하고 부화 시 최종 종과 최고 도달 단계를 확정합니다. 시즌 종료 랭킹에는 사용자·그룹 표시명과 참여 상태를 Snapshot으로 저장합니다.
- 상태: confirmed
- 근거: 알 기간 경험치의 즉시 반영과 시즌 중 그룹 이동 이력을 재현하면서 프로필·그룹 상태가 이후 변경되어도 종료 랭킹 결과를 동일하게 조회하기 위함입니다.
- 영향 문서: database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 퀘스트·활동 상세 컬럼 확정

- 결정: 퀘스트·활동 9개 테이블의 컬럼, 배정 Snapshot, 완료 당시 그룹 귀속, 보상 종류별 원장, 서버 활동 이벤트와 인덱스를 데이터베이스 설계서와 같이 구성합니다. 사진은 저장 키와 판정에 필요한 EXIF 필드만 DB에 저장하고 Binary와 원본 EXIF 전체는 저장하지 않습니다.
- 상태: confirmed
- 근거: 템플릿 변경과 사용자 그룹 이동 이후에도 완료·보상을 재현하고, 클라이언트 누적값이나 불필요한 사진 메타데이터를 신뢰·보관하지 않기 위함입니다.
- 영향 문서: database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 결제·아바타 상세 컬럼과 슬롯 확정

- 결정: 결제·아바타 8개 테이블의 컬럼, 상태머신, 생성 컬럼, UNIQUE와 조회 인덱스를 데이터베이스 설계서와 같이 구성합니다. 아바타 슬롯은 `HEAD`, `FACE`, `BODY`, `ACCESSORY`이며 주문에는 상품·가격·슬롯 Snapshot을 보존합니다. PG 시도와 Webhook은 원문 대신 멱등 키·이벤트 ID·Hash와 정제된 최소 필드만 저장합니다.
- 상태: confirmed
- 근거: 장착·환불 경합과 PG 중복 요청을 재현 가능하게 관리하면서 카드정보·인증정보와 불필요한 외부 Payload 보관을 방지하기 위함입니다.
- 영향 문서: database-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 미접속 강퇴의 마지막 인증 활동 기준 확정

- 결정: 일반 회원의 72시간 미접속 강퇴는 마지막 로그인 시각이 아니라 마지막 인증 활동 시각 `last_active_at`을 기준으로 판정합니다. 로그인 성공 시 `last_login_at`과 `last_active_at`을 함께 기록하고, 이후 성공한 인증 API 요청으로 `last_active_at`을 갱신합니다. 요청이 잦을 때의 DB 쓰기를 줄이기 위해 활동 시각은 최대 10분에 한 번 영속화하며 강퇴 처리 시 최신 값을 다시 검사합니다.
- 상태: confirmed
- 근거: 7일 유지 세션에서 재로그인 없이 계속 활동하는 사용자가 `last_login_at`만으로 72시간 미접속자로 잘못 판정되는 것을 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml
- 대체한 결정: `last_login_at`을 72시간 미접속 강퇴의 직접 기준으로 사용하는 규칙

## 2026-11-02 OAuth 신규 회원 계정 생성 시점 확정

- 결정: OAuth 콜백은 기존 회원의 로그인만 완료하며 신규 사용자는 계정을 생성하지 않고 검증된 소셜 정보와 초대 문맥을 서버의 가입 대기 세션에 저장합니다. 신규 계정은 사용자가 `/signup`에서 닉네임을 확정할 때 생성합니다. 초대 조건이 유효하면 계정과 그룹 멤버십을 같은 트랜잭션으로 만들고, 조건이 실패하면 계정만 생성한 뒤 실패 이유와 그룹 선택 경로를 반환합니다.
- 상태: confirmed
- 근거: Full CSR 회원가입 화면에서 닉네임을 확정하기 전에 불완전한 계정을 생성하지 않고, 초대 링크 재발급·정원 경쟁은 실제 가입 제출 시점에 다시 검사하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 신규 사용자의 OAuth 콜백 처리 중 계정을 생성하는 흐름

## 2026-11-02 외부 효과 API의 공통 멱등 레코드 확정

- 결정: 결제 시작, 환불 요청, 환불 승인과 결과 재처리는 `api_idempotency_records`에서 사용자·Operation·멱등 키·요청 Hash·처리 상태·최초 결과를 관리합니다. 같은 Key와 같은 Hash는 최초 결과를 반환하고 다른 Hash는 거절하며, 처리 중인 동일 요청은 중복 실행하지 않습니다. 멱등 레코드는 연결된 결제·환불 이력과 함께 프로젝트 범위에서 자동 삭제하지 않습니다.
- 상태: confirmed
- 근거: 애플리케이션 재시작과 네트워크 재전송 후에도 클라이언트 명령의 중복 주문·환불·PG 취소를 DB에서 일관되게 차단하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, class-diagram.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 알림 기능을 제외한 39개 업무 테이블 구성. 알림은 계속 보류하고 공통 멱등 레코드를 추가해 40개로 조정합니다.

## 2026-11-02 시즌 중 생성 그룹의 그룹별 72시간 부화 확정

- 결정: 그룹은 시즌 중에도 생성할 수 있고 생성 즉시 현재 시즌의 알 상태 공동 크리처와 0점 시즌 점수로 참가합니다. 기존 그룹은 시즌 시작 시각, 시즌 중 생성된 그룹은 그룹 생성 시각부터 정확히 `3 × 24시간` 동안 알 상태이며 주·부 관리자가 종을 선택할 수 있습니다. 기한까지 선택하지 않으면 무작위 부화합니다. 시즌 종료가 부화 기한보다 먼저 오면 알 상태를 최종 상태로 보존하고 종료 후 부화시키지 않습니다.
- 상태: confirmed
- 근거: 별도 중간 참가 기능이나 화면을 추가하지 않고 기존 그룹 생성 트랜잭션과 그룹별 `selection_deadline_at`, 부화 Scheduler를 재사용하면서 시즌 중 언제든 그룹을 시작할 수 있게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 모든 그룹의 알 기간과 부화 기한을 전역 시즌 시작 후 72시간으로 고정하는 규칙

## 2026-11-02 서비스 공개와 첫 시즌 동시 시작 확정

- 결정: 서비스의 실제 공개 시각과 첫 시즌 시작 시각을 `Asia/Seoul` 기준 같은 월요일 0시로 맞춥니다. 운영 배포와 비공개 점검은 이전에 할 수 있지만 공개 사용자를 위한 프리시즌 화면·API·게임 상태는 구현하지 않습니다.
- 상태: confirmed
- 근거: 첫 시즌 전 공백 상태를 위한 별도 기능을 추가하지 않으면서 공개 사용자가 항상 활성 시즌 안에서 온보딩과 그룹 활동을 시작하도록 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, api-design.md, screen-design.md, common-foundation.yaml
- 대체한 결정: 서비스 개시 후 처음 도래하는 월요일 0시에 첫 시즌을 시작하는 규칙

## 2026-11-02 활성 크리처 종 목록 API 추가 확정

- 결정: 주·부 관리자의 기존 크리처 선택 탭에서 사용할 `GET /api/v1/creature-species`를 추가합니다. 활성 종의 ID, 불변 코드, 이름, 이미지 리소스 Key와 표시 순서만 반환하며 별도 화면은 만들지 않습니다.
- 상태: confirmed
- 근거: 클라이언트가 DB 식별자와 활성 상태를 하드코딩하지 않고 서버가 허용한 종만 선택 요청에 사용할 수 있게 하기 위함입니다.
- 영향 문서: api-design.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 시즌 종료 Scheduler와 Snapshot 확정

- 결정: 시즌 종료 시각에 `SEASON_CLOSE` Scheduler가 시즌을 `CLOSING`으로 선점하고 크리처 최종 상태를 보관하며 개인·그룹 최종 랭킹 Snapshot을 멱등 생성한 뒤 `ENDED`로 확정합니다. 같은 시각의 `SEASON_START`는 이전 시즌 종료가 완료된 뒤 실행합니다.
- 상태: confirmed
- 근거: 중복 실행이나 프로세스 중단에도 종료 랭킹과 크리처 상태가 한 번만 보존되고 새 시즌 초기화가 이전 시즌 결과를 앞지르지 않게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 부 관리자 강퇴의 72시간 조건 통일

- 결정: 부 관리자도 일반 회원의 한 종류로 보고, 주 관리자가 부 관리자를 강퇴할 때도 `last_active_at` 기준 72시간 미활동 조건을 적용합니다. 별도 강퇴 사유나 처리 흐름을 만들지 않고 기존 미접속 강퇴와 `INACTIVE`, `REMOVED_INACTIVE`, `REMOVE_INACTIVE` 기록을 재사용합니다. 주 관리자의 부 관리자 권한 회수에는 활동 조건을 적용하지 않습니다.
- 상태: confirmed
- 근거: 역할은 관리 권한의 차이일 뿐 그룹 강퇴 기준을 다르게 만드는 회원 유형이 아니며, 불필요한 상태와 제재 유형을 추가하지 않기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 주 관리자가 부 관리자를 활동 시각과 관계없이 강퇴할 수 있는 예외

## 2026-11-02 Gemini 그룹 소개 교정 최소 기능 확정

- 결정: LLM 연동은 새 그룹 소개 최대 500자의 맞춤법·띄어쓰기·어색한 표현을 Gemini로 교정하는 단일 기능으로 제한합니다. 사용자가 버튼을 눌렀을 때만 서버가 호출하고, 구조화된 `correctedText` 제안을 사용자가 선택적으로 적용합니다. 자동 저장·자동 게시·대화·요약·판정 기능은 만들지 않습니다.
- 상태: confirmed
- 근거: 서버 간 LLM 요청을 시연하면서 기존 그룹 생성 화면과 `game_groups.description`을 재사용해 프로젝트 범위 증가를 최소화하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 주 관리자 이전 후 기존 관리자 역할 확정

- 결정: 주 관리자 권한을 다른 활성 회원에게 이전하면 대상 회원은 `PRIMARY`, 기존 주 관리자는 `MEMBER`가 됩니다. 기존 주 관리자가 이전에 부 관리자였더라도 별도 부 관리자 권한을 유지하지 않습니다.
- 상태: confirmed
- 근거: 그룹당 주 관리자 한 명 제약을 유지하고 이전 후 기존 관리자의 권한을 예측 가능하게 제거하면서 일반 회원으로 잔류하거나 자진 탈퇴할 수 있게 하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 익명 사용자의 온보딩 상태 확정

- 결정: `/api/v1/me`의 비로그인 상태를 `authenticationState=ANONYMOUS`, `onboardingState=NOT_STARTED`로 반환합니다. 가입 대기, 그룹 선택 필요와 완료 상태도 허용 조합을 고정하고 바닐라 JS Route Guard가 조합별 경로를 직접 매핑합니다.
- 상태: confirmed
- 근거: 가입 대기 세션이 없는 익명 사용자를 `/signup`으로 잘못 보내지 않고 nullable 상태 분기를 피하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 운영 경고를 현재 미해결 상태로 제한

- 결정: 관리자 운영 경고는 별도 경고 이력 테이블 없이 원본 업무 상태에서 현재 미해결 항목만 조회합니다. API에서 `RESOLVED` 조회 Filter와 응답 `status`를 제거하고 원본이 정상화되면 목록에서 제외합니다. 사진 삭제 실패도 경고 원본에 포함합니다.
- 상태: confirmed
- 근거: 알림·경고 이력 기능을 추가하지 않고 현재 운영자가 조치해야 할 이상 상태만 단순하게 제공하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 PG 거래 식별자 저장 기준 확정

- 결정: 내부 주문번호이자 포트원 `paymentId`는 `purchases.order_number`에만 저장합니다. `payment_transactions.provider_transaction_id`에는 승인 행이면 포트원 거래 객체 ID, 취소 행이면 `cancellation.id`를 저장하며 `(provider, provider_transaction_id)` 복합 UNIQUE를 적용합니다.
- 상태: confirmed
- 근거: 승인과 취소의 서로 다른 공급자 식별자를 혼용하지 않고 이후 다른 PG Adapter에서도 공급자별 거래 중복을 안전하게 차단하기 위함입니다.
- 영향 문서: business-rules.md, database-design.md, api-design.md, class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 멤버십 변경의 공통 잠금 순서 확정

- 결정: 가입·탈퇴·강퇴·역할 변경·주 관리자 이전은 영향받는 사용자 행을 사용자 ID 오름차순으로 잠근 뒤 그룹, 관련 멤버십 ID 오름차순, 가입 제한 순으로 잠급니다. 종료 흐름은 멤버십 종료, 인원 감소와 가입 제한 생성을 같은 트랜잭션에서 처리합니다.
- 상태: confirmed
- 근거: 가입과 종료 또는 역할 이전이 동시에 실행될 때 제한 생성 전 재가입과 서로 다른 잠금 순서로 인한 교착을 방지하기 위함입니다.
- 영향 문서: business-rules.md, database-design.md, api-design.md, class-diagram.md, project_plan.md, common-foundation.yaml

## 2026-11-02 그룹 없는 기존 회원의 초대 즉시 가입 확정

- 결정: 이미 가입된 계정이 그룹 없이 초대 링크를 수락하면 링크·정원·현재 소속·24시간 제한을 재검사하고 공통 가입 트랜잭션으로 초대 대상 그룹에 즉시 가입합니다. 성공하면 크리처 홈으로 이동하고 실패하면 그룹 없는 상태와 세션을 유지한 채 실패 이유를 표시합니다.
- 상태: confirmed
- 근거: 초대 수락 의도를 유지하면서 기존 회원에게 다시 일반 그룹 선택을 요구하지 않고 신규·기존 회원 모두 같은 가입 정합성 규칙을 사용하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, screen-design.md, project_plan.md, common-foundation.yaml

## 2026-11-02 회원가입 대기 세션 유효시간 확정

- 결정: 신규 사용자의 가입 대기 세션은 OAuth 콜백 완료 시점부터 고정 30분 동안 유효하며 조회나 입력 활동으로 연장하지 않습니다. 만료되면 검증된 공급자 정보와 초대 문맥을 제거하고 계정을 생성하지 않으며, 로그인 페이지에서 소셜 인증을 다시 시작하게 합니다.
- 상태: confirmed
- 근거: 오래된 소셜 인증 결과와 초대 문맥이 뒤늦게 회원가입에 사용되는 것을 막고 임시 개인정보의 보관 시간을 제한하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, design/wireframes/signup.md, project_plan.md, common-foundation.yaml

## 2026-11-03 OAuth·WebSocket 보안 경계 확정

- 결정: OAuth 로그인 시도를 시작 익명 세션 Hash에 귀속하고 다른 세션의 Callback을 거부합니다. STOMP 연결은 세션·허용 Origin·CSRF를 확인하며 현재 활성 그룹 구독과 정상 종료 외의 클라이언트 `SEND`·`MESSAGE`를 기본 거부합니다.
- 상태: confirmed
- 근거: 로그인 CSRF와 조작된 WebSocket 클라이언트가 다른 사용자 세션이나 그룹 이벤트 경계에 접근하는 문제를 서버에서 차단하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, convention.md, class-diagram.md, implementation-guide.md, project_plan.md, common-foundation.yaml

## 2026-11-03 호출량·사진 처리 자원 한도 확정

- 결정: OAuth는 세션당 10분 5회·IP당 10분 30회, Gemini는 사용자당 1분 5회·KST 하루 30회·전체 동시 2건으로 제한합니다. 사진은 사용자·배정당 5회·사용자당 1분 5회, EXIF 1 MiB·동시 4건·5초 Timeout으로 제한하고 메타데이터만 읽습니다. 단일 인스턴스 메모리 제한은 재시작 시 초기화를 허용합니다.
- 상태: confirmed
- 근거: 2주 단일 인스턴스 MVP에 별도 분산 인프라를 추가하지 않으면서 외부 비용과 비정상 반복 요청·파일 파싱의 자원 고갈을 제한하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, convention.md, class-diagram.md, implementation-guide.md, project_plan.md, common-foundation.yaml

## 2026-11-03 통합 HTTPS·관리자 초기화·외부 상태 매핑 확정

- 결정: 평상시는 localhost HTTP를 사용하고 통합 검증 때만 임시 HTTPS 터널과 `integration` Profile을 사용합니다. 최초 전체 관리자는 환경변수의 공급자·검증 이메일과 일치하는 신규 계정에만 부여합니다. 포트원 취소 상태는 `SUCCEEDED`, `FAILED`, `REQUESTED`를 각각 완료·실패·대기로 변환하며, 타인 소유 개별 자원은 `404`, 관리 기능 역할 부족은 `403`으로 응답합니다.
- 상태: confirmed
- 근거: HTTPS 서버 구축 학습을 프로젝트 범위에 추가하지 않고 모바일·OAuth·Webhook을 실제 검증하며, 관리자 권한 주입·비동기 취소 오판·자원 존재 노출을 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, convention.md, implementation-guide.md, project_plan.md, common-foundation.yaml
- 대체한 결정: 모든 Profile의 세션 쿠키에 `Secure`를 일괄 적용하는 규칙. localhost 전용 `local`만 예외로 하고 `integration`·`prod`에는 계속 필수 적용합니다.

## 2026-11-03 Route·실시간·복구 식별자 계약 보완

- 결정: Route Guard는 사용자 상태와 경로 접근 등급을 함께 평가해 그룹 없는 일반 회원의 로그인 전용 화면과 그룹 없는 전체 관리자의 관리자 화면을 허용합니다. STOMP는 검증된 `CONNECT`, 현재 그룹 `SUBSCRIBE`, 현재 연결 구독의 `UNSUBSCRIBE`, 정상 `DISCONNECT`만 허용합니다. 서명 검증된 결제·취소 Webhook의 `paymentId`는 내부 주문 식별보다 먼저 별도 컬럼에 보존합니다.
- 상태: confirmed
- 근거: 그룹 소속 여부가 상점·결제·전체 관리자 권한을 잘못 차단하는 문제, 기본 거부 정책이 정상 구독 해제를 막는 문제와 `purchase_id` 식별 전 Webhook 실패를 복구하지 못하는 문제를 방지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, convention.md, class-diagram.md, implementation-guide.md, project_plan.md, common-foundation.yaml

## 2026-11-03 최초 관리자·탈퇴·랭킹·로컬 DB 계약 보완

- 결정: 최초 전체 관리자는 이메일이 아니라 환경변수의 공급자·불변 공급자 사용자 ID와 일치하는 신규 계정에만 부여하며 기존 일반 회원은 자동 승격하지 않습니다. 자기 탈퇴 Endpoint는 모든 그룹 회원에게 열되 주 관리자는 유일한 활성 구성원일 때만 성공합니다. 랭킹 응답은 `meta.currentSubject` 키를 항상 제공하고 대상이 없으면 객체 전체를 `null`로 반환합니다. 스피어 실행에서는 H2 기반 `local` 설정을 MySQL `ddl-auto=update`, 별도 MySQL `test` DB를 `create-drop`으로 전환합니다.
- 상태: confirmed
- 근거: Naver 이메일에는 별도 검증 상태가 없으므로 관리자 신원은 불변 공급자 식별자를 사용하고, 권한표·응답 Null 계약·실행 DB를 확정된 업무 규칙과 맞추기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, convention.md, implementation-guide.md, project_plan.md, common-foundation.yaml
- 대체한 결정: `INITIAL_SUPER_ADMIN_EMAIL`과 검증 이메일로 최초 관리자를 식별하는 규칙.

## 2026-11-02 Java 기준 버전 확정

- 결정: 백엔드의 로컬 개발, Gradle Toolchain과 CI 실행 환경은 Java 21 LTS로 통일합니다. 기준 Package는 `com.team.sharedcreature`를 사용하며 현재 초기 생성 소스와 일치합니다.
- 상태: confirmed
- 근거: 팀원별 JDK 차이로 인한 빌드 편차를 방지하고 설계 식별자와 실제 구현 Package를 일치시키기 위함입니다.
- 영향 문서: requirements.md, convention.md, project_plan.md, common-foundation.yaml

## 2026-11-02 기준 Package 정정

- 결정: 설계 기준 Package를 `com.team.sharedcreature`로 정정하고 모든 설계·실행 계약 표기를 이 값으로 통일합니다.
- 상태: confirmed
- 근거: 사용자가 기준 Package를 바로잡았으며 현재 초기 Java 소스와 설정도 `com.team.sharedcreature`를 사용하고 있습니다.
- 영향 문서: requirements.md, convention.md, project_plan.md, decision-log.md, common-foundation.yaml

## 2026-11-02 서비스 표시명 확정

- 결정: 사용자 화면, 브라우저 제목과 OAuth 애플리케이션 표시명은 `다 같이 키우는 생명체`를 사용합니다. `공동 육성 게임`은 서비스 유형 설명으로 사용하고 저장소·Artifact·프로젝트 Name은 `shared-creature-game`으로 유지합니다.
- 상태: confirmed
- 근거: 사용자용 제품명과 개발·배포 식별자를 구분해 UI와 외부 인증 설정의 명칭을 일관되게 유지하기 위함입니다.
- 영향 문서: index.md, requirements.md, project_plan.md, common-foundation.yaml

## 2026-11-02 포트원 V2 카카오페이 단일 채널 확정

- 결정: 테스트 결제는 포트원 V2의 카카오페이 PG 채널 하나만 지원합니다. 결제 요청은 `payMethod=EASY_PAY`, `currency=KRW`로 고정하고 PC는 `IFRAME`, 모바일은 `REDIRECTION`을 사용하며 전체 취소만 제공합니다.
- 상태: confirmed
- 근거: 프로젝트 결제 시연 범위를 하나의 간편결제 채널로 제한하면서 포트원 V2의 결제 조회·Webhook·취소 정합성 흐름에 집중하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, screen-design.md, design/wireframes/shop.md, project_plan.md, common-foundation.yaml

## 2026-11-02 EXIF 위치 인증을 MVP 핵심 기능으로 확정

- 결정: `PHOTO_EXIF` 위치·촬영 시각 인증과 `photo_submissions`는 조건부 후보가 아니라 MVP 핵심 필수 기능입니다. Android Chrome과 iOS Safari 실기기 검증 실패는 기능 제외 사유가 아니라 MVP 미완료로 처리하며, 일일·주간 사진 템플릿은 중 난이도 활성 순회에 포함합니다.
- 상태: confirmed
- 대체한 결정: EXIF 실기기 기술 검증 실패 시 사진 퀘스트와 `photo_submissions`를 제외할 수 있다는 조건부 구현 정책
- 근거: 야외 활동과 공동 육성 경험을 연결하는 사진 위치 인증이 서비스의 핵심 차별 기능이기 때문입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, screen-design.md, design/wireframes/quests.md, project_plan.md, common-foundation.yaml

## 2026-11-02 EXIF 업로드 형식을 JPEG로 제한

- 결정: MVP의 EXIF 사진 업로드는 최대 10 MiB의 JPEG만 지원합니다. 서버는 `image/jpeg` MIME 형식과 JPEG SOI Signature `FF D8 FF`를 함께 확인하고 HEIC·HEIF를 포함한 다른 형식은 `PHOTO_FORMAT_UNSUPPORTED`로 거절합니다.
- 상태: confirmed
- 근거: Docker와 네이티브 이미지 변환 의존성 없이 Android Chrome과 iOS Safari의 핵심 EXIF 판정 흐름을 2주 안에 안정적으로 검증하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, screen-design.md, design/wireframes/quests.md, project_plan.md, common-foundation.yaml

## 2026-11-02 EXIF 판정을 최소 검증으로 제한

- 결정: 사진 판정은 GPS 존재, 위도·경도 유효 범위와 촬영 시각이 배정의 시작 이상·종료 미만인지까지만 확인합니다. EXIF Offset이 없으면 `Asia/Seoul`로 해석하며 실외 여부, 현재 위치, 목표 장소·반경, 사진 간 거리, 위변조와 동일 사진 재사용은 판정하지 않습니다.
- 상태: confirmed
- 근거: EXIF로 실제 야외 활동을 과도하게 단정하지 않고 프로젝트의 핵심 메타데이터 추출·서버 판정 흐름만 구현하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, database-design.md, screen-design.md, design/wireframes/quests.md, project_plan.md, common-foundation.yaml

## 2026-11-02 초보 개발자 기준 REST API 계약 완성도 보강

- 결정: 설계 문서만으로 백엔드와 Vanilla JavaScript 프론트엔드를 구현할 수 있도록 58개 Operation에 성공 HTTP 상태와 Operation별 업무 오류를 연결하고, Endpoint별 Query DTO·고정 정렬·공통 Envelope·Page·Rank Meta·오류 필드·OAuth Redirect·PortOne Webhook 소비 필드를 상세 정의합니다. 임의 `object` 차트 응답은 강타입 DTO로 교체하고 JSON·Multipart 호출 예시를 제공합니다.
- 상태: confirmed
- 결정: EXIF 사진 제출은 POST 안에서 최종 판정을 끝내 외부 응답을 `VALID`, `INVALID`로 제한하며 `PENDING`은 내부 DB 처리 상태로만 사용합니다. 별도 상태 조회 API는 추가하지 않습니다.
- 결정: 요청 ID에서 화면·Operation·DTO·Service·테이블까지 따라가는 구현 시작 안내서와 화면별 API 연결표를 제공하고, 백엔드·프론트엔드 구현 및 목 데이터 완료 체크리스트를 같은 진입점에 둡니다.
- 근거: 초보 팀원이 Endpoint 목록과 여러 절 사이의 암묵적 규칙을 추론하지 않고 표와 예시를 그대로 구현하고 테스트할 수 있게 하기 위함입니다.
- 영향 문서: index.md, requirements.md, screen-design.md, api-design.md, class-diagram.md, implementation-guide.md, project_plan.md, common-foundation.yaml

## 2026-11-02 시즌·멤버십·퀘스트 완료 공통 직렬화 확정

- 결정: 퀘스트 완료는 시즌 공유 잠금 → 사용자 → 그룹 → 활성 멤버십 → 그룹 시즌 → 완료 UNIQUE → 개인 기여도 → 그룹 점수 → 활성 크리처 순으로 잠그고 시즌·멤버십·배정 귀속을 잠금 아래 재검사합니다.
- 결정: 시즌 종료는 같은 시즌 행의 배타 잠금으로 진행 중 완료의 종료를 기다린 뒤 `CLOSING`으로 전환합니다. 탈퇴·강퇴와 완료는 사용자·멤버십 잠금을 먼저 얻은 흐름을 선행시킵니다.
- 상태: confirmed
- 근거: 시즌 최종 Snapshot 뒤 보상이 추가되거나 멤버십 종료 뒤 이전 그룹에 보상이 반영되는 경합을 제거하기 위함입니다.
- 영향 문서: business-rules.md, database-design.md, class-diagram.md, common-foundation.yaml

## 2026-11-03 비즈니스 경계·장애 복구 정책 확정

- 결정: 유일한 구성원인 주 관리자의 기존 탈퇴 요청은 그룹 `INACTIVE`, 초대 링크 무효화, 현재 그룹 시즌 종료와 크리처 보관을 함께 처리합니다. 기록은 보존하고 별도 해산 API를 추가하지 않습니다.
- 결정: `CANCEL_PENDING`과 Webhook `PENDING`의 고착 기준은 5분이며 `REFUND_RECONCILIATION`, `PAYMENT_RECONCILIATION`을 5분 주기로 실행합니다. 복구는 PortOne 최신 상태 조회를 먼저 수행하고 기존 멱등·정합성 Service를 재사용합니다.
- 결정: 사진은 촬영·업로드 접수 시각이 배정 기간 안이고 판정·보상 Commit이 마감과 시즌 `CLOSING` 전에 완료돼야 보상합니다. 마감 유예와 이월은 제공하지 않습니다.
- 결정: 최대 10분의 활동 시각 저장 지연을 고려해 저장값 기준 72시간 10분 경과 후 미접속 강퇴를 허용합니다. 사용자 안내는 72시간을 유지합니다.
- 결정: 활동 Event에 발생 당시 멤버십·그룹 시즌을 저장하고 `AGGREGATE`는 완료 시점과 같은 귀속의 Event만 집계합니다. 이전 그룹 활동 이력은 보존하지만 새 그룹 보상에는 사용하지 않습니다.
- 상태: confirmed
- 근거: 1인 그룹의 종료 불가 상태를 해소하고 외부 시스템 중단·기간 경계·활동 저장 지연·그룹 이동에서도 보상과 결제 상태를 일관되게 유지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, screen-design.md, api-design.md, convention.md, class-diagram.md, implementation-guide.md, project_plan.md, common-foundation.yaml

## 2026-11-03 스키마 관리 방식을 기존 학습 범위로 제한

- 결정: Flyway와 Liquibase는 도입하지 않습니다. 일반 테이블과 컬럼은 `local`의 `ddl-auto=update`, `test`의 `ddl-auto=create-drop`으로 관리하고, MySQL STORED 생성 컬럼·UNIQUE 인덱스와 Spring Session JDBC 테이블처럼 Hibernate가 안전하게 관리하기 어려운 특수 스키마만 재실행 가능한 별도 SQL로 관리합니다.
- 상태: confirmed
- 근거: 팀이 학습한 JPA·Hibernate 범위 안에서 구현하고, 학습하지 않은 마이그레이션 도구 도입으로 인한 일정·운영 부담을 피하면서 MySQL 전용 정합성 제약은 데이터베이스에서 유지하기 위함입니다.
- 영향 문서: convention.md, common-foundation.yaml

## 2026-11-03 공통 기반 구현의 최소 범위 확정

- 결정: 공통 기반 구현에는 공통 REST API와 MySQL 환경, 설계된 40개 업무 테이블의 전체 JPA Entity·기본 `JpaRepository`, 로컬 기본 샘플 데이터, 경로 등급별 Spring Security 인증·인가, 중앙 집중 REST 예외 처리, Vite Router·공통 API Client·Theme CSS, 백엔드와 프론트엔드를 잇는 견본 기능 한 개와 동일 계약의 목 데이터를 포함합니다.
- 결정: Google 소셜 로그인은 서버 세션·가입 대기·내부 사용자 연결과 교체 가능한 공급자 경계까지 공통 기반에서 구현하고, 실제 Google API 자격 증명과 외부 연동 마무리는 사용자가 이어서 수행할 수 있도록 `README.md`에 로컬 설정 절차를 기록합니다. Naver 공급자 구현은 후속 범위로 미룹니다.
- 결정: `data.sql`에는 스키마 DDL이 아니라 로컬 실행용 기본 샘플 데이터 DML만 둡니다. MySQL 전용 생성 컬럼·UNIQUE와 Spring Session 테이블 DDL은 별도 특수 스키마 SQL로 관리합니다.
- 상태: confirmed
- 근거: 초보 팀원이 공통 구조와 견본 수직 흐름을 복사해 기능을 확장할 수 있는 최소 실행 기반을 먼저 제공하면서, 학습하지 않은 도구와 즉시 필요하지 않은 Naver 외부 연동을 이번 공통 기반에서 분리하기 위함입니다.
- 영향 문서: convention.md, implementation-guide.md, README.md, common-foundation.yaml

## 2026-11-03 공통 기반 견본 기능과 와이어프레임 적용 범위 확정

- 결정: 공통 기반의 견본 수직 기능은 `createGroup`으로 구현합니다. Controller·Service·Repository·DTO·API Client·그룹 생성 Page·동일 계약의 목 데이터와 자동 테스트를 포함하고, 그룹·주 관리자 멤버십·최초 관리자 변경 이력·활성 초대 링크·현재 시즌 참여·알 상태 크리처·0점 시즌 그룹 점수를 설계된 단일 트랜잭션으로 생성합니다.
- 결정: 기존 Markdown 와이어프레임은 화면 구조를 확인하는 설계 참고 자료로 유지합니다. 이번 공통 기반에서는 HTML 와이어프레임 변환, 공통 와이어프레임 CSS와 GitHub Pages 공개를 구현·검증 대상으로 사용하지 않습니다.
- 상태: confirmed
- 근거: `createGroup`이 인증 사용자, Validation, 트랜잭션, 여러 Repository, REST 오류와 Form 처리를 함께 보여 주어 후속 기능의 기준 예제로 적합하며, HTML/Pages 변환은 실행 가능한 애플리케이션 기반을 만드는 현재 우선순위에 직접 필요하지 않기 때문입니다.
- 영향 문서: screen-design.md, implementation-guide.md, common-foundation.yaml

## 2026-11-03 그룹 테이블명을 MySQL 비예약어로 변경

- 결정: 그룹 기준 테이블명을 `groups`에서 `game_groups`로 변경합니다. Java 도메인명 `Group`과 REST·화면 경로의 `/groups`는 변경하지 않습니다.
- 상태: confirmed
- 대체한 결정: 그룹 기준 테이블명을 `groups`로 사용한 기존 데이터베이스 설계
- 근거: `GROUPS`가 MySQL 8.0.2부터 예약어이므로 Hibernate `ddl-auto=update`가 생성하는 FK DDL에서 구문 오류가 발생하는 문제를 제거하기 위함입니다.
- 영향 문서: database-design.md, decision-log.md, implementation-guide.md, project_plan.md, common-foundation.yaml

## 2026-11-03 PHOTO_EXIF 실기기 검증 대상 조정

- 결정: `PHOTO_EXIF`의 MVP 필수 구현 정책은 유지하되, 필수 실기기 검증 대상을 Android Chrome으로 한정합니다. iOS Safari는 MVP 검증 대상에서 제외하며 정상 동작을 보장하지 않습니다. Android Chrome 검증 실패는 MVP 미완료로 처리합니다.
- 상태: confirmed
- 대체한 결정: Android Chrome과 iOS Safari 양쪽 실기기 검증을 MVP 완료 조건으로 요구한 정책
- 근거: 2주 MVP의 검증 범위를 Android 환경으로 제한하면서 EXIF 위치·촬영 시각 인증이라는 핵심 기능은 유지하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, project_plan.md, screen-design.md, docs/spikes/exif-validation.md

## 2026-11-05 Scheduler 누락 실행·서버 재기동 정상화 확정

- 결정: 정시 Cron, 초기화 완료 뒤 애플리케이션 기동 이벤트와 5분 주기 점검은 DB 상태·주입 `Clock`·KST 기간으로 누락 업무를 판정하는 공통 정상화 Service를 호출합니다. `(job_type, scheduled_for)`의 `scheduled_for`는 실제 실행 시각이 아니라 업무 논리 경계이며, 실행 이력이 없어도 업무 상태에서 누락을 발견합니다.
- 결정: 서버 중단 중 만료된 퀘스트 기간과 중간 4주 시즌은 소급 생성하지 않고 보상도 이월하지 않습니다. 마지막 저장 시즌을 정상 종료한 뒤 최초 시즌의 4주 달력에서 현재 구간을 계산하여 마지막 시즌 번호의 다음 번호로 현재 시즌 하나만 만들고, 현재 일일·주간 배정만 복구합니다.
- 결정: 종료된 시즌은 논리 부화 기한이 지났더라도 미부화 크리처를 소급 부화시키지 않고 저장된 마지막 알 상태 그대로 Snapshot과 `ENDED`를 확정합니다. 서버 중단 때문에 사용자가 경험하지 못한 부화를 과거 시점에 생성하지 않습니다.
- 결정: 현재 구간 시즌은 `SCHEDULED`로 생성하고 기존 활성 그룹의 `GroupSeason`, 알 상태 `GroupCreature`, 0점 `SeasonGroupScore`를 UNIQUE 기반으로 전량 초기화한 뒤 `ACTIVE`로 전환합니다. 기존 그룹과 시즌 도중 새 그룹 모두 `selection_deadline_at=실제 최초 참여 생성 시각+72시간`을 사용하며, 재실행해도 저장된 기한을 연장하지 않습니다.
- 결정: `SCHEDULED`와 `CLOSING`에서는 부화하지 않고 `ACTIVE` 일반 부화만 허용합니다. 관리자가 선택한 기존 `selected_at`은 보존하며 미선택 무작위 선택의 `selected_at`, 크리처 `hatched_at`과 `updated_at`은 실제 부화 처리 시각을 기록합니다. 복구 시즌 종료까지 72시간이 남지 않으면 알 상태로 시즌을 마칩니다.
- 결정: 만료 전 `SCHEDULED`는 저장된 참여와 기한을 유지하며 누락 초기화를 재개합니다. `now >= end_at`인 `SCHEDULED`는 `ACTIVE`로 전환하거나 누락 참여·알·점수·퀘스트를 생성하지 않습니다. 시즌 배타 잠금 아래 `SCHEDULED → CLOSING`, 이미 Commit된 `GroupSeason` 일괄 `ENDED`, 시즌 `ENDED`를 한 트랜잭션으로 종결하여 일반 `ACTIVE` 종료의 영속 `CLOSING`과 구분하고, 알·선택·점수 원본은 보존합니다.
- 결정: 한 번도 `ACTIVE`가 아니었던 만료 `SCHEDULED`에는 개인·그룹 랭킹 Snapshot을 만들지 않습니다. 참여가 전혀 없어도 빈 종료 시즌으로 종결하고, 일부 초기화된 그룹을 0점 랭킹으로 노출하지 않습니다. 종결 뒤 현재 4주 구간 시즌 하나만 다음 시즌 번호로 생성합니다.
- 결정: 시즌 의존 API는 `ACTIVE`와 `start_at <= now < end_at`을 함께 검사합니다. 현재 퀘스트 기간이 없거나 `PENDING/FAILED`이면 빈 목록 대신 기존 `409 CONCURRENT_MODIFICATION`을 반환합니다. `createGroup`, 사진 제출과 `seasonId` 미지정 랭킹에도 같은 현재 시즌 보호를 적용하며 새 오류 코드는 추가하지 않습니다.
- 결정: 시즌 그룹 초기화·일반 `ACTIVE` 부화·사진·결제처럼 대상이 많은 작업은 100건 단위로 처리해 중단 시 다음 기동 또는 5분 점검에서 이어갑니다. 초기화 대상이 남은 시즌 활성화 작업은 성공 처리하지 않습니다. 첫 시즌 기준 레코드가 없으면 자동 추정하지 않고 개발·시연 환경의 명시적 시드 준비를 요구합니다.
- 상태: confirmed
- 근거: 24시간 가동되지 않는 개발 환경에서 누락 업무를 복구하면서 존재하지 않았던 과거 시즌·퀘스트·보상·부화를 만들지 않고, 사용자가 복구 시즌에서도 알 상태와 종 선택 기간을 경험하게 하며 기존 UNIQUE·Lease·오류 계약을 재사용하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, database-design.md, api-design.md, class-diagram.md
- 후속 구현: Issue #24 시즌·퀘스트 Scheduler에서 고정 `Clock` 기반 재기동·부분 실패·중복 트리거 테스트로 검증합니다.
- 대체한 결정: 2026-11-02 그룹별 72시간 부화 결정 중 장기 중단 후 복구되거나 초기화가 지연된 기존 그룹의 `시즌 시작 시각+72시간` 기준. 이 경우 실제 최초 그룹 시즌 참여 생성 시각+72시간을 사용하며, 시즌 중 새 그룹의 실제 참여 생성 시각 기준과 시즌 종료가 먼저면 알 상태로 종료하는 정책은 유지합니다.

## 2026-11-10 관리자 일별 활동 원본 추가

- 결정: 성공한 인증 API 활동을 KST 날짜와 당시 멤버십별로 보존하는 `user_daily_activities`를 추가하여 업무 테이블을 41개로 조정합니다. `users.last_active_at`의 강퇴 판단과 10분 갱신 정책은 유지합니다.
- 상태: confirmed
- 대체한 결정: 일별 관리자 활동 통계를 최종 `last_active_at`만으로 계산하고 업무 테이블을 40개로 유지한 구성
- 근거: 기간 전체와 날짜별 고유 활동 사용자 및 활동 당시 그룹 집계를 과거 시점 기준으로 재현하기 위함입니다.
- 영향 문서: requirements.md, business-rules.md, api-design.md, database-design.md, screen-design.md, class-diagram.md, decision-log.md

## 2026-11-10 PHOTO_EXIF 보상 실패 이력 정책 간소화

- 결정: 확정된 업무 조건 실패는 사진 제출을 `INVALID`로 기록합니다. 기간 종료에는 기존 `QUEST_PERIOD_ENDED`, 그 밖의 확정된 업무 오류에는 `QUEST_COMPLETION_FAILED`를 사용합니다.
- 결정: 서버 내부 오류로 보상 결과가 불확실하면 `PENDING`을 유지할 수 있습니다. 1시간을 넘긴 미확정 `PENDING`은 기존 Scheduler가 `PHOTO_PROCESSING_FAILED`로 `INVALID` 확정하며, 5초 EXIF 파싱 Timeout에는 기존 `PHOTO_PROCESSING_TIMEOUT`을 사용합니다.
- 결정: `QUEST_COMPLETION_FAILED`와 `PHOTO_PROCESSING_FAILED`는 HTTP 오류 코드가 아닌 사진 제출 이력의 실패 사유입니다. 기존 업무 오류의 HTTP 응답을 유지하며 별도 복구·재지급 시스템은 MVP 범위에 포함하지 않습니다.
- 상태: confirmed
- 근거: 2주·4인 MVP의 구현 범위를 늘리지 않으면서 보상 실패를 EXIF 파싱 Timeout으로 잘못 기록하는 문제를 방지하기 위함입니다.
- 영향 문서: api-design.md, business-rules.md, decision-log.md
- 연관: Issue #18, PR #76
