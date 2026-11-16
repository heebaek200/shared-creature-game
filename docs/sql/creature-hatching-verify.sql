-- 현재 ACTIVE 시즌의 그룹별 부화 기한과 크리처 상태를 조회합니다.
-- 읽기 전용이며 테스트 데이터나 운영 데이터를 수정하지 않습니다.
SELECT
    gs.id                  AS group_season_id
  , gs.status              AS group_season_status
  , gs.selection_deadline_at
  , gs.selection_source
  , gs.selected_at
  , gc.id                  AS creature_id
  , gc.growth_stage
  , gc.total_experience
  , gc.selection_source    AS creature_selection_source
  , gc.hatched_at
FROM group_seasons AS gs
JOIN seasons AS s
  ON s.id = gs.season_id
LEFT JOIN group_creatures AS gc
  ON gc.group_season_id = gs.id
WHERE s.status = 'ACTIVE'
ORDER BY gs.id ASC;

-- ACTIVE 시즌 중 72시간이 지난 PREPARING 그룹 시즌의 개수를 확인합니다.
-- 실제 Scheduler는 대상별 잠금 재검사 후 부화시키므로 이 값은 관찰용입니다.
SELECT
    COUNT(*) AS due_preparing_groups
FROM group_seasons AS gs
JOIN seasons AS s
  ON s.id = gs.season_id
JOIN game_groups AS g
  ON g.id = gs.group_id
WHERE s.status = 'ACTIVE'
  AND s.start_at <= UTC_TIMESTAMP(6)
  AND s.end_at > UTC_TIMESTAMP(6)
  AND gs.status = 'PREPARING'
  AND g.status = 'ACTIVE'
  AND gs.selection_deadline_at <= UTC_TIMESTAMP(6)
  AND gs.selection_deadline_at < s.end_at;

-- 부화 완료 상태와 크리처 EGG 상태가 불일치하는 행을 확인합니다.
SELECT
    gs.id AS group_season_id
  , gs.status AS group_season_status
  , gc.growth_stage
  , gs.selection_source
  , gc.selection_source AS creature_selection_source
FROM group_seasons AS gs
JOIN group_creatures AS gc
  ON gc.group_season_id = gs.id
WHERE (gs.status = 'ACTIVE' AND gc.growth_stage = 'EGG')
   OR (gs.status = 'PREPARING' AND gc.growth_stage <> 'EGG')
ORDER BY gs.id ASC;
