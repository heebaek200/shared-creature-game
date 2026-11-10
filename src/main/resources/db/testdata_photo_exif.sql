-- PHOTO_EXIF Android Chrome 수동 테스트용 데이터
-- 개발 DB에서만 실행하세요. 성공 제출 시 실제 보상이 확정됩니다.

SET @test_template_code = 'D-X-EXIF-TEST';
SET @test_now_utc = UTC_TIMESTAMP(6);
SET @test_period_date = DATE(@test_now_utc + INTERVAL 9 HOUR);
SET @test_period_start = TIMESTAMP(@test_period_date) - INTERVAL 9 HOUR;
SET @test_period_end = @test_period_start + INTERVAL 1 DAY;

-- 현재 시각에 유효한 활성 시즌을 찾습니다.
SET @test_season_id = (
    SELECT id
      FROM seasons
     WHERE status = 'ACTIVE'
       AND start_at <= @test_now_utc
       AND end_at > @test_now_utc
     ORDER BY start_at DESC
     LIMIT 1
);

-- 테스트 템플릿이 없으면 추가합니다. 기존 rotation_order와 겹치지 않게 정합니다.
SET @test_rotation_order = (
    SELECT COALESCE(MAX(rotation_order), 0) + 1
      FROM quest_templates
     WHERE period_type = 'DAILY'
       AND difficulty = 'MEDIUM'
);

INSERT INTO quest_templates (
    code, period_type, difficulty, status, rotation_order, created_at, updated_at
)
SELECT
    @test_template_code, 'DAILY', 'MEDIUM', 'ACTIVE', @test_rotation_order,
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM quest_templates
    WHERE code = @test_template_code
);

SET @test_template_id = (
    SELECT id
      FROM quest_templates
     WHERE code = @test_template_code
     LIMIT 1
);

-- 검증 기준과 보상을 가진 템플릿 버전을 추가합니다.
INSERT INTO quest_template_versions (
    quest_template_id, version_number, title, description,
    verification_type, verification_parameters,
    creature_experience, contribution_score, created_at
)
SELECT
    @test_template_id,
    1,
    '사진 EXIF 테스트',
    'GPS와 촬영 시각이 포함된 JPEG 사진을 제출합니다.',
    'PHOTO_EXIF',
    JSON_OBJECT(),
    10,
    10,
    UTC_TIMESTAMP(6)
FROM DUAL
WHERE @test_template_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM quest_template_versions
    WHERE quest_template_id = @test_template_id
      AND version_number = 1
);

SET @test_template_version_id = (
    SELECT id
      FROM quest_template_versions
     WHERE quest_template_id = @test_template_id
       AND version_number = 1
     LIMIT 1
);

-- 오늘(KST) 일일 기간이 없으면 현재 활성 시즌 안에 생성합니다.
INSERT INTO quest_periods (
    season_id, period_type, period_date, start_at, end_at, status,
    failure_code, failure_detail, version, created_at, updated_at
)
SELECT
    @test_season_id,
    'DAILY',
    @test_period_date,
    @test_period_start,
    @test_period_end,
    'ACTIVE',
    NULL,
    NULL,
    0,
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
FROM DUAL
WHERE @test_season_id IS NOT NULL
  AND EXISTS (
    SELECT 1
    FROM seasons
    WHERE id = @test_season_id
      AND start_at <= @test_period_start
      AND end_at >= @test_period_end
)
  AND NOT EXISTS (
    SELECT 1
    FROM quest_periods
    WHERE period_type = 'DAILY'
      AND start_at = @test_period_start
);

SET @test_period_id = (
    SELECT id
      FROM quest_periods
     WHERE season_id = @test_season_id
       AND period_type = 'DAILY'
       AND status = 'ACTIVE'
       AND start_at <= @test_now_utc
       AND end_at > @test_now_utc
     ORDER BY start_at DESC
     LIMIT 1
);

SET @test_slot_number = (
    SELECT COALESCE(MAX(slot_number), 0) + 1
      FROM quest_assignments
     WHERE quest_period_id = @test_period_id
);

-- 같은 기간에 이 템플릿 배정이 없을 때만 추가합니다.
INSERT INTO quest_assignments (
    quest_period_id, quest_template_id, quest_template_version_id,
    slot_number, title_snapshot, description_snapshot, difficulty_snapshot,
    verification_type_snapshot, verification_parameters_snapshot,
    creature_experience_snapshot, contribution_score_snapshot, created_at
)
SELECT
    @test_period_id,
    @test_template_id,
    @test_template_version_id,
    @test_slot_number,
    '사진 EXIF 테스트',
    'GPS와 촬영 시각이 포함된 JPEG 사진을 제출합니다.',
    'MEDIUM',
    'PHOTO_EXIF',
    JSON_OBJECT(),
    10,
    10,
    UTC_TIMESTAMP(6)
FROM DUAL
WHERE @test_period_id IS NOT NULL
  AND @test_template_id IS NOT NULL
  AND @test_template_version_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM quest_assignments
    WHERE quest_period_id = @test_period_id
      AND quest_template_id = @test_template_id
);

-- 생성되거나 재사용된 테스트 배정 ID를 확인합니다.
SELECT
    assignment.id AS assignment_id,
    assignment.title_snapshot,
    assignment.verification_type_snapshot,
    period.period_date,
    period.start_at,
    period.end_at
FROM quest_assignments assignment
         JOIN quest_periods period
              ON period.id = assignment.quest_period_id
WHERE assignment.quest_template_id = @test_template_id
  AND period.id = @test_period_id;