-- R-2 수동 점검 (local/dev MySQL). 다른 데이터를 변경하지 않습니다.
-- 각 난이도 최소치: DAILY 5/4/3, WEEKLY 6/4/4.
SELECT period_type
     , difficulty
     , COUNT(*) AS active_template_count
  FROM quest_templates
 WHERE status = 'ACTIVE'
 GROUP BY period_type, difficulty
 ORDER BY period_type, difficulty;

-- 표준 코드 28개가 모두 활성화됐고 버전이 존재하는지 검증합니다.
SELECT t.period_type
     , t.difficulty
     , COUNT(*) AS canonical_template_count
     , SUM(CASE WHEN t.status = 'ACTIVE' THEN 1 ELSE 0 END) AS canonical_active_count
     , SUM(CASE WHEN v.id IS NOT NULL THEN 1 ELSE 0 END) AS versioned_count
  FROM quest_templates AS t
  LEFT JOIN quest_template_versions AS v
    ON v.quest_template_id = t.id
   AND v.version_number = 1
 WHERE t.code REGEXP '^[DW]-[LMHX]-[0-9]{2}$'
 GROUP BY t.period_type, t.difficulty
 ORDER BY t.period_type, t.difficulty;

-- 공식 템플릿 v1과 보상·검증 타입 샘플 (실제 배정은 최종 최신 버전 사용).
SELECT t.code
     , t.status
     , v.verification_type
     , v.verification_parameters
     , v.creature_experience
     , v.contribution_score
  FROM quest_templates AS t
  JOIN quest_template_versions AS v
    ON v.quest_template_id = t.id
   AND v.version_number = 1
 WHERE t.code IN ('D-H-03', 'D-X-01', 'W-H-02', 'W-X-01')
 ORDER BY t.code;

-- 기존 임시 템플릿은 보존·비활성화됐는지 확인합니다.
SELECT code
     , status
     , rotation_order
  FROM quest_templates
 WHERE code IN ('DAILY_WATER', 'DAILY_STRETCH', 'WEEKLY_WALK')
 ORDER BY code;
