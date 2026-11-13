-- #93 로컬 수동 테스트용 시나리오 데이터
-- MySQL 8 / Spring SQL initialization 전용입니다.
-- mt93_* 자연키를 가진 행만 생성하며 기존 행을 갱신하거나 삭제하지 않습니다.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET @mt93_now = CAST(UTC_TIMESTAMP(6) AS DATETIME(6));
SET @mt93_user_marker = CAST('2026-09-03 00:00:00' AS DATETIME(6));
SET @mt93_history_anchor = CAST('2026-11-13 00:00:00' AS DATETIME(6));

CREATE TEMPORARY TABLE IF NOT EXISTS mt93_numbers (
    n INT NOT NULL PRIMARY KEY
);

DELETE FROM mt93_numbers;

INSERT INTO mt93_numbers (n)
WITH RECURSIVE sequence AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1
      FROM sequence
     WHERE n < 500
)
SELECT n
  FROM sequence;

-- ============================================================
-- 회원 60명: 활성 56명(장기 미접속 8명), 탈퇴 3명, 과거 관리자 1명
-- ============================================================

INSERT INTO users (
    status
  , global_role
  , last_login_at
  , last_active_at
  , withdrawn_at
  , version
  , created_at
  , updated_at
)
SELECT CASE WHEN numbers.n <= 56 THEN 'ACTIVE' ELSE 'WITHDRAWN' END
     , CASE WHEN numbers.n = 60 THEN 'SUPER_ADMIN' ELSE 'USER' END
     , CASE
           WHEN numbers.n BETWEEN 49 AND 56
               THEN CAST(@mt93_now - INTERVAL 45 DAY AS DATETIME(6))
           ELSE CAST(@mt93_now - INTERVAL MOD(numbers.n, 14) DAY AS DATETIME(6))
       END
     , CASE
           WHEN numbers.n BETWEEN 49 AND 56
               THEN CAST(@mt93_now - INTERVAL 45 DAY AS DATETIME(6))
           ELSE CAST(@mt93_now - INTERVAL MOD(numbers.n, 14) DAY AS DATETIME(6))
       END
     , CASE
           WHEN numbers.n > 56
               THEN CAST(@mt93_now - INTERVAL 30 DAY AS DATETIME(6))
           ELSE NULL
       END
     , 0
     , DATE_ADD(@mt93_user_marker, INTERVAL numbers.n SECOND)
     , CASE
           WHEN numbers.n > 56
               THEN CAST(@mt93_now - INTERVAL 30 DAY AS DATETIME(6))
           ELSE CAST(@mt93_now AS DATETIME(6))
       END
  FROM mt93_numbers AS numbers
 WHERE numbers.n <= 60
   AND NOT EXISTS (
       SELECT 1
         FROM users AS existing
        WHERE existing.created_at = DATE_ADD(@mt93_user_marker, INTERVAL numbers.n SECOND)
          AND existing.global_role = CASE WHEN numbers.n = 60 THEN 'SUPER_ADMIN' ELSE 'USER' END
   );

INSERT INTO user_profiles (
    user_id
  , nickname
  , normalized_nickname
  , version
  , created_at
  , updated_at
)
SELECT seed_user.id
     , CONCAT('MT93 사용자 ', LPAD(numbers.n, 3, '0'))
     , CONCAT('mt93_user_', LPAD(numbers.n, 3, '0'))
     , 0
     , seed_user.created_at
     , seed_user.updated_at
  FROM mt93_numbers AS numbers
  JOIN users AS seed_user
    ON seed_user.id = (
       SELECT MIN(candidate.id)
         FROM users AS candidate
        WHERE candidate.created_at = DATE_ADD(@mt93_user_marker, INTERVAL numbers.n SECOND)
          AND candidate.global_role = CASE WHEN numbers.n = 60 THEN 'SUPER_ADMIN' ELSE 'USER' END
    )
 WHERE numbers.n <= 60
   AND NOT EXISTS (
       SELECT 1
         FROM user_profiles AS existing
        WHERE existing.user_id = seed_user.id
           OR existing.normalized_nickname = CONCAT('mt93_user_', LPAD(numbers.n, 3, '0'))
   );

-- ============================================================
-- 공개 그룹 12개와 활성 52건·종료 68건의 멤버십
-- ============================================================

CREATE TEMPORARY TABLE IF NOT EXISTS mt93_group_plan (
    group_number       INT NOT NULL PRIMARY KEY
  , primary_user_number INT NULL
);

DELETE FROM mt93_group_plan;

INSERT INTO mt93_group_plan (group_number, primary_user_number) VALUES
    (1, 1)
  , (2, 20)
  , (3, 23)
  , (4, 27)
  , (5, 31)
  , (6, 35)
  , (7, 39)
  , (8, 43)
  , (9, 47)
  , (10, 50)
  , (11, NULL)
  , (12, NULL);

INSERT INTO game_groups (
    name
  , normalized_name
  , description
  , visibility
  , status
  , capacity
  , current_member_count
  , primary_admin_user_id
  , version
  , created_at
  , updated_at
)
SELECT CONCAT('MT93 테스트 그룹 ', LPAD(plan.group_number, 2, '0'))
     , CONCAT('mt93_group_', LPAD(plan.group_number, 2, '0'))
     , CONCAT('이슈 #93 수동 테스트 시나리오 그룹 ', LPAD(plan.group_number, 2, '0'))
     , 'PUBLIC'
     , CASE WHEN plan.group_number <= 10 THEN 'ACTIVE' ELSE 'INACTIVE' END
     , 20
     , CASE plan.group_number
           WHEN 1 THEN 19
           WHEN 2 THEN 3
           WHEN 3 THEN 4
           WHEN 4 THEN 4
           WHEN 5 THEN 4
           WHEN 6 THEN 4
           WHEN 7 THEN 4
           WHEN 8 THEN 4
           WHEN 9 THEN 3
           WHEN 10 THEN 3
           ELSE 0
       END
     , primary_profile.user_id
     , 0
     , @mt93_history_anchor - INTERVAL 70 DAY + INTERVAL plan.group_number HOUR
     , @mt93_history_anchor - INTERVAL 70 DAY + INTERVAL plan.group_number HOUR
  FROM mt93_group_plan AS plan
  LEFT JOIN user_profiles AS primary_profile
    ON primary_profile.normalized_nickname =
       CONCAT('mt93_user_', LPAD(plan.primary_user_number, 3, '0'))
 WHERE NOT EXISTS (
       SELECT 1
         FROM game_groups AS existing
        WHERE existing.normalized_name =
              CONCAT('mt93_group_', LPAD(plan.group_number, 2, '0'))
 );

INSERT INTO group_memberships (
    group_id
  , user_id
  , role
  , status
  , joined_at
  , ended_at
  , ended_by_user_id
  , exit_reason
  , version
  , created_at
  , updated_at
)
SELECT seed_group.id
     , profile.user_id
     , CASE
           WHEN numbers.n IN (1, 20, 23, 27, 31, 35, 39, 43, 47, 50) THEN 'PRIMARY'
           WHEN numbers.n IN (2, 21, 24, 28, 32, 36, 40, 44, 48, 51) THEN 'DEPUTY'
           ELSE 'MEMBER'
       END
     , 'ACTIVE'
     , CASE
           WHEN numbers.n = 1 THEN @mt93_history_anchor - INTERVAL 68 DAY
           ELSE @mt93_history_anchor - INTERVAL (30 - MOD(numbers.n, 10)) DAY
       END
     , NULL
     , NULL
     , NULL
     , 0
     , CASE
           WHEN numbers.n = 1 THEN @mt93_history_anchor - INTERVAL 68 DAY
           ELSE @mt93_history_anchor - INTERVAL (30 - MOD(numbers.n, 10)) DAY
       END
     , @mt93_history_anchor - INTERVAL MOD(numbers.n, 24) HOUR
  FROM mt93_numbers AS numbers
  JOIN user_profiles AS profile
    ON profile.normalized_nickname = CONCAT('mt93_user_', LPAD(numbers.n, 3, '0'))
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = CONCAT(
       'mt93_group_',
       LPAD(
           CASE
               WHEN numbers.n BETWEEN 1 AND 19 THEN 1
               WHEN numbers.n BETWEEN 20 AND 22 THEN 2
               WHEN numbers.n BETWEEN 23 AND 26 THEN 3
               WHEN numbers.n BETWEEN 27 AND 30 THEN 4
               WHEN numbers.n BETWEEN 31 AND 34 THEN 5
               WHEN numbers.n BETWEEN 35 AND 38 THEN 6
               WHEN numbers.n BETWEEN 39 AND 42 THEN 7
               WHEN numbers.n BETWEEN 43 AND 46 THEN 8
               WHEN numbers.n BETWEEN 47 AND 49 THEN 9
               ELSE 10
           END,
           2,
           '0'
       )
    )
 WHERE numbers.n <= 52
   AND NOT EXISTS (
       SELECT 1
         FROM group_memberships AS existing
        WHERE existing.user_id = profile.user_id
          AND existing.group_id = seed_group.id
   );

INSERT INTO group_memberships (
    group_id
  , user_id
  , role
  , status
  , joined_at
  , ended_at
  , ended_by_user_id
  , exit_reason
  , version
  , created_at
  , updated_at
)
SELECT seed_group.id
     , profile.user_id
     , 'MEMBER'
     , CASE WHEN numbers.n <= 5 OR MOD(numbers.n, 2) = 0 THEN 'REMOVED' ELSE 'LEFT' END
     , @mt93_history_anchor
       - INTERVAL (65 - MOD(numbers.n, 10) + FLOOR((numbers.n - 1) / 60)) DAY
     , @mt93_history_anchor - INTERVAL (40 - MOD(numbers.n, 10)) DAY
     , CASE
           WHEN numbers.n <= 5 OR MOD(numbers.n, 2) = 0 THEN historical_admin.user_id
           ELSE profile.user_id
       END
     , CASE
           WHEN numbers.n <= 5 THEN 'REPORTED'
           WHEN MOD(numbers.n, 2) = 0 THEN 'INACTIVE'
           ELSE 'SELF'
       END
     , 0
     , @mt93_history_anchor
       - INTERVAL (65 - MOD(numbers.n, 10) + FLOOR((numbers.n - 1) / 60)) DAY
     , @mt93_history_anchor - INTERVAL (40 - MOD(numbers.n, 10)) DAY
  FROM mt93_numbers AS numbers
  JOIN user_profiles AS profile
    ON profile.normalized_nickname = CONCAT(
       'mt93_user_',
       LPAD(CASE WHEN numbers.n <= 5 THEN numbers.n + 1 ELSE MOD(numbers.n + 5, 60) + 1 END, 3, '0')
    )
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = CONCAT(
       'mt93_group_',
       LPAD(CASE WHEN numbers.n <= 5 THEN 1 ELSE MOD(numbers.n - 1, 12) + 1 END, 2, '0')
    )
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
 WHERE numbers.n <= 68
   AND NOT EXISTS (
       SELECT 1
         FROM group_memberships AS existing
        WHERE existing.group_id = seed_group.id
          AND existing.user_id = profile.user_id
          AND existing.joined_at = @mt93_history_anchor
              - INTERVAL (65 - MOD(numbers.n, 10) + FLOOR((numbers.n - 1) / 60)) DAY
   );

INSERT INTO group_invite_links (
    group_id
  , token_hash
  , status
  , issued_by_user_id
  , issued_at
  , invalidated_at
  , created_at
)
SELECT seed_group.id
     , SHA2(CONCAT('MT93-INVITE-', plan.group_number), 256)
     , CASE WHEN plan.group_number <= 10 THEN 'ACTIVE' ELSE 'INVALIDATED' END
     , COALESCE(primary_profile.user_id, historical_admin.user_id)
     , seed_group.created_at
     , CASE WHEN plan.group_number > 10 THEN seed_group.updated_at ELSE NULL END
     , seed_group.created_at
  FROM mt93_group_plan AS plan
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = CONCAT('mt93_group_', LPAD(plan.group_number, 2, '0'))
  LEFT JOIN user_profiles AS primary_profile
    ON primary_profile.normalized_nickname =
       CONCAT('mt93_user_', LPAD(plan.primary_user_number, 3, '0'))
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
 WHERE NOT EXISTS (
       SELECT 1
         FROM group_invite_links AS existing
        WHERE existing.token_hash = SHA2(CONCAT('MT93-INVITE-', plan.group_number), 256)
 );

INSERT INTO group_admin_changes (
    group_id
  , target_user_id
  , actor_user_id
  , change_type
  , previous_role
  , new_role
  , created_at
)
SELECT seed_group.id
     , primary_profile.user_id
     , primary_profile.user_id
     , 'INITIAL_PRIMARY'
     , NULL
     , 'PRIMARY'
     , primary_membership.joined_at
  FROM mt93_group_plan AS plan
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = CONCAT('mt93_group_', LPAD(plan.group_number, 2, '0'))
  JOIN user_profiles AS primary_profile
    ON primary_profile.normalized_nickname =
       CONCAT('mt93_user_', LPAD(plan.primary_user_number, 3, '0'))
  JOIN group_memberships AS primary_membership
    ON primary_membership.group_id = seed_group.id
   AND primary_membership.user_id = primary_profile.user_id
   AND primary_membership.status = 'ACTIVE'
 WHERE plan.group_number <= 10
   AND NOT EXISTS (
       SELECT 1
         FROM group_admin_changes AS existing
        WHERE existing.group_id = seed_group.id
          AND existing.target_user_id = primary_profile.user_id
          AND existing.change_type = 'INITIAL_PRIMARY'
 );

INSERT INTO group_admin_changes (
    group_id
  , target_user_id
  , actor_user_id
  , change_type
  , previous_role
  , new_role
  , created_at
)
SELECT seed_group.id
     , deputy_membership.user_id
     , primary_profile.user_id
     , 'GRANT_DEPUTY'
     , 'MEMBER'
     , 'DEPUTY'
     , deputy_membership.joined_at
  FROM mt93_group_plan AS plan
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = CONCAT('mt93_group_', LPAD(plan.group_number, 2, '0'))
  JOIN user_profiles AS primary_profile
    ON primary_profile.normalized_nickname =
       CONCAT('mt93_user_', LPAD(plan.primary_user_number, 3, '0'))
  JOIN group_memberships AS deputy_membership
    ON deputy_membership.group_id = seed_group.id
   AND deputy_membership.role = 'DEPUTY'
   AND deputy_membership.status = 'ACTIVE'
 WHERE plan.group_number <= 10
   AND NOT EXISTS (
       SELECT 1
         FROM group_admin_changes AS existing
        WHERE existing.group_id = seed_group.id
          AND existing.target_user_id = deputy_membership.user_id
          AND existing.change_type = 'GRANT_DEPUTY'
 );

INSERT INTO group_join_restrictions (
    user_id
  , source_membership_id
  , reason
  , restricted_from
  , restricted_until
  , created_at
  , updated_at
)
SELECT profile.user_id
     , membership.id
     , membership.exit_reason
     , CASE
           WHEN numbers.n BETWEEN 53 AND 56 THEN @mt93_now - INTERVAL 1 HOUR
           ELSE membership.ended_at
       END
     , CASE
           WHEN numbers.n BETWEEN 53 AND 56 THEN @mt93_now + INTERVAL 23 HOUR
           ELSE membership.ended_at + INTERVAL 1 DAY
       END
     , membership.ended_at
     , membership.ended_at
  FROM mt93_numbers AS numbers
  JOIN user_profiles AS profile
    ON profile.normalized_nickname = CONCAT('mt93_user_', LPAD(numbers.n, 3, '0'))
  JOIN group_memberships AS membership
    ON membership.id = (
       SELECT latest_membership.id
         FROM group_memberships AS latest_membership
        WHERE latest_membership.user_id = profile.user_id
          AND latest_membership.status IN ('LEFT', 'REMOVED')
        ORDER BY latest_membership.ended_at DESC
        LIMIT 1
    )
 WHERE numbers.n IN (7, 8, 9, 10, 53, 54, 55, 56)
   AND NOT EXISTS (
       SELECT 1
         FROM group_join_restrictions AS existing
        WHERE existing.user_id = profile.user_id
   );

-- ============================================================
-- 실제 활성 시즌의 그룹 시즌·크리처·점수
-- ============================================================

SET @mt93_season_id = (
    SELECT season.id
      FROM seasons AS season
     WHERE season.status = 'ACTIVE'
       AND season.start_at <= @mt93_now
       AND season.end_at > @mt93_now
     ORDER BY season.start_at DESC
     LIMIT 1
);

INSERT INTO group_seasons (
    group_id
  , season_id
  , selection_deadline_at
  , selected_species_id
  , selected_by_user_id
  , selection_source
  , selected_at
  , status
  , version
  , created_at
  , updated_at
)
SELECT seed_group.id
     , @mt93_season_id
     , CASE
           WHEN plan.group_number = 10
               THEN LEAST(@mt93_now + INTERVAL 1 DAY, active_season.end_at - INTERVAL 1 MICROSECOND)
           ELSE @mt93_now - INTERVAL 7 DAY
       END
     , CASE WHEN plan.group_number = 10 THEN NULL ELSE species.id END
     , CASE WHEN plan.group_number = 10 THEN NULL ELSE primary_profile.user_id END
     , CASE WHEN plan.group_number = 10 THEN NULL ELSE 'ADMIN' END
     , CASE WHEN plan.group_number = 10 THEN NULL ELSE @mt93_now - INTERVAL 8 DAY END
     , CASE WHEN plan.group_number = 10 THEN 'PREPARING' ELSE 'ACTIVE' END
     , 0
     , @mt93_now - INTERVAL 10 DAY
     , @mt93_now - INTERVAL 1 DAY
  FROM mt93_group_plan AS plan
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = CONCAT('mt93_group_', LPAD(plan.group_number, 2, '0'))
  JOIN seasons AS active_season
    ON active_season.id = @mt93_season_id
  JOIN user_profiles AS primary_profile
    ON primary_profile.normalized_nickname =
       CONCAT('mt93_user_', LPAD(plan.primary_user_number, 3, '0'))
  LEFT JOIN creature_species AS species
    ON species.display_order = MOD(plan.group_number - 1, 3) + 1
   AND species.status = 'ACTIVE'
 WHERE plan.group_number <= 10
   AND @mt93_season_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
         FROM group_seasons AS existing
        WHERE existing.group_id = seed_group.id
          AND existing.season_id = @mt93_season_id
   );

-- 같은 실제 시즌에서는 최초 시드 실행일을 고정해 날짜·주간 경계 재실행도 같은 기간을 사용합니다.
SET @mt93_season_seed_anchor = COALESCE((
    SELECT MIN(group_season.created_at) + INTERVAL 10 DAY
      FROM group_seasons AS group_season
      JOIN game_groups AS seed_group
        ON seed_group.id = group_season.group_id
     WHERE seed_group.normalized_name LIKE 'mt93_group_%'
       AND group_season.season_id = @mt93_season_id
), @mt93_now);

INSERT INTO group_creatures (
    group_season_id
  , species_id
  , selection_source
  , total_experience
  , growth_stage
  , growth_stage_reached_at
  , hatched_at
  , status
  , version
  , created_at
  , updated_at
)
SELECT group_season.id
     , group_season.selected_species_id
     , group_season.selection_source
     , 0
     , CASE WHEN group_season.selected_species_id IS NULL THEN 'EGG' ELSE 'HATCHLING' END
     , CASE WHEN group_season.selected_species_id IS NULL THEN NULL ELSE @mt93_now - INTERVAL 7 DAY END
     , CASE WHEN group_season.selected_species_id IS NULL THEN NULL ELSE @mt93_now - INTERVAL 7 DAY END
     , 'ACTIVE'
     , 0
     , group_season.created_at
     , group_season.updated_at
  FROM group_seasons AS group_season
  JOIN game_groups AS seed_group
    ON seed_group.id = group_season.group_id
 WHERE seed_group.normalized_name LIKE 'mt93_group_%'
   AND group_season.season_id = @mt93_season_id
   AND NOT EXISTS (
       SELECT 1
         FROM group_creatures AS existing
        WHERE existing.group_season_id = group_season.id
   );

INSERT INTO season_group_scores (
    group_season_id
  , score
  , score_reached_at
  , version
  , created_at
  , updated_at
)
SELECT group_season.id
     , 0
     , NULL
     , 0
     , group_season.created_at
     , group_season.updated_at
  FROM group_seasons AS group_season
  JOIN game_groups AS seed_group
    ON seed_group.id = group_season.group_id
 WHERE seed_group.normalized_name LIKE 'mt93_group_%'
   AND group_season.season_id = @mt93_season_id
   AND NOT EXISTS (
       SELECT 1
         FROM season_group_scores AS existing
        WHERE existing.group_season_id = group_season.id
   );

INSERT INTO season_member_contributions (
    group_season_id
  , user_id
  , latest_membership_id
  , contribution_score
  , score_reached_at
  , participant_status
  , version
  , created_at
  , updated_at
)
SELECT group_season.id
     , membership.user_id
     , membership.id
     , 0
     , membership.joined_at
     , 'ACTIVE'
     , 0
     , membership.joined_at
     , membership.updated_at
  FROM group_memberships AS membership
  JOIN game_groups AS seed_group
    ON seed_group.id = membership.group_id
  JOIN group_seasons AS group_season
    ON group_season.group_id = seed_group.id
   AND group_season.season_id = @mt93_season_id
 WHERE seed_group.normalized_name LIKE 'mt93_group_%'
   AND membership.status = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1
         FROM season_member_contributions AS existing
        WHERE existing.group_season_id = group_season.id
          AND existing.user_id = membership.user_id
   );

-- ============================================================
-- 현재 KST 일일·주간 기간과 공식 퀘스트 10개
-- ============================================================

SET @mt93_daily_start = TIMESTAMP(DATE(@mt93_season_seed_anchor + INTERVAL 9 HOUR)) - INTERVAL 9 HOUR;
SET @mt93_daily_end = @mt93_daily_start + INTERVAL 1 DAY;
SET @mt93_weekly_start =
    @mt93_daily_start - INTERVAL WEEKDAY(DATE(@mt93_season_seed_anchor + INTERVAL 9 HOUR)) DAY;
SET @mt93_weekly_end = @mt93_weekly_start + INTERVAL 7 DAY;

INSERT INTO quest_periods (
    season_id
  , period_type
  , period_date
  , start_at
  , end_at
  , status
  , failure_code
  , failure_detail
  , version
  , created_at
  , updated_at
)
SELECT @mt93_season_id
     , 'DAILY'
     , DATE(@mt93_daily_start + INTERVAL 9 HOUR)
     , @mt93_daily_start
     , @mt93_daily_end
     , 'ACTIVE'
     , NULL
     , NULL
     , 0
     , @mt93_now
     , @mt93_now
  FROM DUAL
 WHERE @mt93_season_id IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM seasons AS season
        WHERE season.id = @mt93_season_id
          AND season.start_at <= @mt93_daily_start
          AND season.end_at >= @mt93_daily_end
   )
   AND NOT EXISTS (
       SELECT 1
         FROM quest_periods AS existing
        WHERE existing.period_type = 'DAILY'
          AND existing.start_at = @mt93_daily_start
   );

INSERT INTO quest_periods (
    season_id
  , period_type
  , period_date
  , start_at
  , end_at
  , status
  , failure_code
  , failure_detail
  , version
  , created_at
  , updated_at
)
SELECT @mt93_season_id
     , 'DAILY'
     , DATE(@mt93_daily_start - INTERVAL numbers.n DAY + INTERVAL 9 HOUR)
     , @mt93_daily_start - INTERVAL numbers.n DAY
     , @mt93_daily_end - INTERVAL numbers.n DAY
     , 'CLOSED'
     , NULL
     , NULL
     , 0
     , @mt93_daily_end - INTERVAL numbers.n DAY
     , @mt93_daily_end - INTERVAL numbers.n DAY
  FROM mt93_numbers AS numbers
 WHERE numbers.n BETWEEN 1 AND 6
   AND @mt93_season_id IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM seasons AS season
        WHERE season.id = @mt93_season_id
          AND season.start_at <= @mt93_daily_start - INTERVAL numbers.n DAY
          AND season.end_at >= @mt93_daily_end - INTERVAL numbers.n DAY
   )
   AND NOT EXISTS (
       SELECT 1
         FROM quest_periods AS existing
        WHERE existing.period_type = 'DAILY'
          AND existing.start_at = @mt93_daily_start - INTERVAL numbers.n DAY
   );

INSERT INTO quest_periods (
    season_id
  , period_type
  , period_date
  , start_at
  , end_at
  , status
  , failure_code
  , failure_detail
  , version
  , created_at
  , updated_at
)
SELECT @mt93_season_id
     , 'WEEKLY'
     , DATE(@mt93_weekly_start + INTERVAL 9 HOUR)
     , @mt93_weekly_start
     , @mt93_weekly_end
     , 'ACTIVE'
     , NULL
     , NULL
     , 0
     , @mt93_now
     , @mt93_now
  FROM DUAL
 WHERE @mt93_season_id IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM seasons AS season
        WHERE season.id = @mt93_season_id
          AND season.start_at <= @mt93_weekly_start
          AND season.end_at >= @mt93_weekly_end
   )
   AND NOT EXISTS (
       SELECT 1
         FROM quest_periods AS existing
        WHERE existing.period_type = 'WEEKLY'
          AND existing.start_at = @mt93_weekly_start
   );

CREATE TEMPORARY TABLE IF NOT EXISTS mt93_assignment_plan (
    period_type  VARCHAR(30) NOT NULL
  , template_code VARCHAR(50) NOT NULL
  , slot_number INT NOT NULL
  , PRIMARY KEY (period_type, slot_number)
);

DELETE FROM mt93_assignment_plan;

INSERT INTO mt93_assignment_plan (period_type, template_code, slot_number) VALUES
    ('DAILY', 'D-L-01', 1)
  , ('DAILY', 'D-M-01', 2)
  , ('DAILY', 'D-H-01', 3)
  , ('WEEKLY', 'W-L-01', 1)
  , ('WEEKLY', 'W-L-02', 2)
  , ('WEEKLY', 'W-L-03', 3)
  , ('WEEKLY', 'W-L-04', 4)
  , ('WEEKLY', 'W-L-05', 5)
  , ('WEEKLY', 'W-L-06', 6)
  , ('WEEKLY', 'W-M-01', 7);

INSERT INTO quest_assignments (
    quest_period_id
  , quest_template_id
  , quest_template_version_id
  , slot_number
  , title_snapshot
  , description_snapshot
  , difficulty_snapshot
  , verification_type_snapshot
  , verification_parameters_snapshot
  , creature_experience_snapshot
  , contribution_score_snapshot
  , created_at
)
SELECT period.id
     , template.id
     , version.id
     , plan.slot_number
     , version.title
     , version.description
     , template.difficulty
     , version.verification_type
     , version.verification_parameters
     , version.creature_experience
     , version.contribution_score
     , @mt93_now
  FROM mt93_assignment_plan AS plan
  JOIN quest_periods AS period
    ON (
       (
           plan.period_type = 'DAILY'
           AND period.period_type = 'DAILY'
           AND period.start_at BETWEEN @mt93_daily_start - INTERVAL 6 DAY
                                   AND @mt93_daily_start
       )
       OR
       (
           plan.period_type = 'WEEKLY'
           AND period.period_type = 'WEEKLY'
           AND period.start_at = @mt93_weekly_start
       )
    )
  JOIN quest_templates AS template
    ON template.code = plan.template_code
  JOIN quest_template_versions AS version
    ON version.quest_template_id = template.id
   AND version.version_number = 1
 WHERE NOT EXISTS (
       SELECT 1
         FROM quest_assignments AS any_assignment
        WHERE any_assignment.quest_period_id = period.id
 )
 ORDER BY period.start_at, plan.slot_number;

-- SERVER_EVENT와 주간 집계 퀘스트가 참조하는 실제 체크인 근거를 먼저 구성합니다.
INSERT INTO user_activity_events (
    user_id
  , event_type
  , source_type
  , source_id
  , membership_id
  , group_season_id
  , business_date
  , occurred_at
  , deduplication_key
  , metadata
  , created_at
)
SELECT membership.user_id
     , 'SERVICE_CHECK_IN'
     , 'MANUAL_TEST_SEED'
     , membership.id
     , membership.id
     , group_season.id
     , period.period_date
     , LEAST(@mt93_season_seed_anchor, period.start_at + INTERVAL 7 HOUR)
     , CONCAT('MT93:CHECKIN:', membership.id, ':', DATE_FORMAT(period.period_date, '%Y%m%d'))
     , JSON_OBJECT('seed', 'issue-93', 'morning', TRUE)
     , LEAST(@mt93_season_seed_anchor, period.start_at + INTERVAL 7 HOUR)
  FROM group_memberships AS membership
  JOIN user_profiles AS profile
    ON profile.user_id = membership.user_id
   AND profile.normalized_nickname LIKE 'mt93_user_%'
  JOIN group_seasons AS group_season
    ON group_season.group_id = membership.group_id
   AND group_season.season_id = @mt93_season_id
  JOIN quest_periods AS period
    ON period.season_id = @mt93_season_id
   AND period.period_type = 'DAILY'
   AND period.start_at BETWEEN @mt93_daily_start - INTERVAL 6 DAY AND @mt93_daily_start
 WHERE membership.status = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1
         FROM user_activity_events AS existing
        WHERE existing.deduplication_key =
              CONCAT('MT93:CHECKIN:', membership.id, ':', DATE_FORMAT(period.period_date, '%Y%m%d'))
   );

INSERT INTO service_check_ins (
    user_id
  , activity_event_id
  , check_in_date
  , checked_in_at
  , is_morning
  , created_at
)
SELECT event.user_id
     , event.id
     , event.business_date
     , event.occurred_at
     , TRUE
     , event.created_at
  FROM user_activity_events AS event
 WHERE event.deduplication_key LIKE 'MT93:CHECKIN:%'
   AND NOT EXISTS (
       SELECT 1
         FROM service_check_ins AS existing
        WHERE existing.activity_event_id = event.id
   );

CREATE TEMPORARY TABLE IF NOT EXISTS mt93_completion_plan (
    user_id             BIGINT      NOT NULL
  , assignment_id       BIGINT      NOT NULL
  , group_season_id     BIGINT      NOT NULL
  , membership_id       BIGINT      NOT NULL
  , completed_at        DATETIME(6) NOT NULL
  , period_start_at     DATETIME(6) NOT NULL
  , experience_amount   BIGINT      NOT NULL
  , contribution_amount BIGINT      NOT NULL
  , PRIMARY KEY (user_id, assignment_id)
);

DELETE FROM mt93_completion_plan;

-- 그룹 1은 GROWING, 그룹 10은 EGG, 나머지는 HATCHLING 시나리오가 되도록 500건을 고정합니다.
INSERT INTO mt93_completion_plan (
    user_id
  , assignment_id
  , group_season_id
  , membership_id
  , completed_at
  , period_start_at
  , experience_amount
  , contribution_amount
)
SELECT ranked.user_id
     , ranked.assignment_id
     , ranked.group_season_id
     , ranked.membership_id
     , ranked.completed_at
     , ranked.period_start_at
     , ranked.experience_amount
     , ranked.contribution_amount
  FROM (
       SELECT candidates.*
            , ROW_NUMBER() OVER (
                  ORDER BY candidates.seed_priority
                         , candidates.period_start_at
                         , candidates.assignment_id
                         , candidates.user_number
              ) AS seed_order
         FROM (
              SELECT membership.user_id
                   , assignment.id AS assignment_id
                   , group_season.id AS group_season_id
                   , membership.id AS membership_id
                   , period.start_at AS period_start_at
                   , CAST(RIGHT(profile.normalized_nickname, 3) AS UNSIGNED) AS user_number
                   , assignment.creature_experience_snapshot AS experience_amount
                   , assignment.contribution_score_snapshot AS contribution_amount
                   , CASE
                         WHEN seed_group.normalized_name = 'mt93_group_01'
                          AND (
                              period.period_type = 'DAILY'
                               OR (period.period_type = 'WEEKLY' AND assignment.slot_number <= 3)
                          ) THEN 0
                         WHEN seed_group.normalized_name = 'mt93_group_01' THEN 2
                         ELSE 1
                     END AS seed_priority
                   , CASE
                         WHEN period.period_type = 'WEEKLY' THEN @mt93_season_seed_anchor
                         WHEN assignment.verification_type_snapshot = 'SERVER_EVENT'
                             THEN LEAST(@mt93_season_seed_anchor, period.start_at + INTERVAL 8 HOUR)
                         ELSE LEAST(
                             @mt93_season_seed_anchor,
                             period.start_at
                             + INTERVAL MOD(
                                 CAST(RIGHT(profile.normalized_nickname, 3) AS UNSIGNED)
                                 * 17 + assignment.slot_number,
                                 600
                               ) MINUTE
                         )
                     END AS completed_at
                FROM group_memberships AS membership
                JOIN user_profiles AS profile
                  ON profile.user_id = membership.user_id
                 AND profile.normalized_nickname LIKE 'mt93_user_%'
                JOIN game_groups AS seed_group
                  ON seed_group.id = membership.group_id
                JOIN group_seasons AS group_season
                  ON group_season.group_id = membership.group_id
                 AND group_season.season_id = @mt93_season_id
                JOIN quest_periods AS period
                  ON period.season_id = @mt93_season_id
                 AND (
                     (
                         period.period_type = 'DAILY'
                         AND period.start_at BETWEEN @mt93_daily_start - INTERVAL 6 DAY
                                                 AND @mt93_daily_start
                     )
                      OR
                     (period.period_type = 'WEEKLY' AND period.start_at = @mt93_weekly_start)
                 )
                JOIN quest_assignments AS assignment
                  ON assignment.quest_period_id = period.id
               WHERE membership.status = 'ACTIVE'
              ) AS candidates
       ) AS ranked
 WHERE ranked.seed_order <= 500;

INSERT INTO quest_completions (
    user_id
  , quest_assignment_id
  , group_season_id
  , membership_id
  , completed_at
  , creature_total_experience_after
  , creature_growth_stage_after
  , group_season_score_after
  , created_at
)
SELECT planned.user_id
     , planned.assignment_id
     , planned.group_season_id
     , planned.membership_id
     , planned.completed_at
     , planned.creature_balance
     , CASE
           WHEN planned.creature_balance >= 150000 THEN 'COMPLETE'
           WHEN planned.creature_balance >= 97500 THEN 'MATURE'
           WHEN planned.creature_balance >= 45000 THEN 'GROWING'
           ELSE 'HATCHLING'
       END
     , planned.group_score_balance
     , planned.completed_at
  FROM (
       SELECT completion_plan.*
            , SUM(completion_plan.experience_amount) OVER (
                  PARTITION BY completion_plan.group_season_id
                  ORDER BY completion_plan.completed_at
                         , completion_plan.assignment_id
                         , completion_plan.user_id
              ) AS creature_balance
            , SUM(completion_plan.contribution_amount) OVER (
                  PARTITION BY completion_plan.group_season_id
                  ORDER BY completion_plan.completed_at
                         , completion_plan.assignment_id
                         , completion_plan.user_id
              ) AS group_score_balance
         FROM mt93_completion_plan AS completion_plan
       ) AS planned
 WHERE NOT EXISTS (
       SELECT 1
         FROM quest_completions AS existing
        WHERE existing.user_id = planned.user_id
          AND existing.quest_assignment_id = planned.assignment_id
   );

INSERT INTO quest_reward_ledgers (
    quest_completion_id
  , reward_type
  , amount
  , group_creature_id
  , member_contribution_id
  , group_score_id
  , applied_at
  , created_at
)
SELECT completion.id
     , 'CREATURE_EXPERIENCE'
     , assignment.creature_experience_snapshot
     , creature.id
     , NULL
     , NULL
     , completion.completed_at
     , completion.completed_at
  FROM quest_completions AS completion
  JOIN user_profiles AS profile
    ON profile.user_id = completion.user_id
   AND profile.normalized_nickname LIKE 'mt93_user_%'
  JOIN quest_assignments AS assignment
    ON assignment.id = completion.quest_assignment_id
  JOIN group_creatures AS creature
    ON creature.group_season_id = completion.group_season_id
 WHERE NOT EXISTS (
       SELECT 1
         FROM quest_reward_ledgers AS existing
        WHERE existing.quest_completion_id = completion.id
          AND existing.reward_type = 'CREATURE_EXPERIENCE'
 );

INSERT INTO quest_reward_ledgers (
    quest_completion_id
  , reward_type
  , amount
  , group_creature_id
  , member_contribution_id
  , group_score_id
  , applied_at
  , created_at
)
SELECT completion.id
     , 'CONTRIBUTION'
     , assignment.contribution_score_snapshot
     , NULL
     , contribution.id
     , group_score.id
     , completion.completed_at
     , completion.completed_at
  FROM quest_completions AS completion
  JOIN user_profiles AS profile
    ON profile.user_id = completion.user_id
   AND profile.normalized_nickname LIKE 'mt93_user_%'
  JOIN quest_assignments AS assignment
    ON assignment.id = completion.quest_assignment_id
  JOIN season_member_contributions AS contribution
    ON contribution.group_season_id = completion.group_season_id
   AND contribution.user_id = completion.user_id
  JOIN season_group_scores AS group_score
    ON group_score.group_season_id = completion.group_season_id
 WHERE NOT EXISTS (
       SELECT 1
         FROM quest_reward_ledgers AS existing
        WHERE existing.quest_completion_id = completion.id
          AND existing.reward_type = 'CONTRIBUTION'
 );

INSERT INTO creature_experience_ledgers (
    group_creature_id
  , quest_completion_id
  , experience_amount
  , balance_after
  , occurred_at
  , created_at
)
SELECT creature.id
     , completion.id
     , assignment.creature_experience_snapshot
     , completion.creature_total_experience_after
     , completion.completed_at
     , completion.completed_at
  FROM quest_completions AS completion
  JOIN user_profiles AS profile
    ON profile.user_id = completion.user_id
   AND profile.normalized_nickname LIKE 'mt93_user_%'
  JOIN quest_assignments AS assignment
    ON assignment.id = completion.quest_assignment_id
  JOIN group_creatures AS creature
    ON creature.group_season_id = completion.group_season_id
 WHERE NOT EXISTS (
       SELECT 1
         FROM creature_experience_ledgers AS existing
        WHERE existing.quest_completion_id = completion.id
 );

UPDATE season_member_contributions AS contribution
JOIN (
    SELECT completion.group_season_id
         , completion.user_id
         , SUM(assignment.contribution_score_snapshot) AS total_score
         , MAX(completion.completed_at) AS reached_at
      FROM quest_completions AS completion
      JOIN user_profiles AS profile
        ON profile.user_id = completion.user_id
       AND profile.normalized_nickname LIKE 'mt93_user_%'
      JOIN quest_assignments AS assignment
        ON assignment.id = completion.quest_assignment_id
     GROUP BY completion.group_season_id, completion.user_id
) AS aggregate
  ON aggregate.group_season_id = contribution.group_season_id
 AND aggregate.user_id = contribution.user_id
   SET contribution.contribution_score = aggregate.total_score
     , contribution.score_reached_at = aggregate.reached_at
     , contribution.updated_at = aggregate.reached_at
     , contribution.version = contribution.version + 1
 WHERE contribution.version = 0;

UPDATE season_group_scores AS group_score
JOIN (
    SELECT completion.group_season_id
         , SUM(assignment.contribution_score_snapshot) AS total_score
         , MAX(completion.completed_at) AS reached_at
      FROM quest_completions AS completion
      JOIN user_profiles AS profile
        ON profile.user_id = completion.user_id
       AND profile.normalized_nickname LIKE 'mt93_user_%'
      JOIN quest_assignments AS assignment
        ON assignment.id = completion.quest_assignment_id
     GROUP BY completion.group_season_id
) AS aggregate
  ON aggregate.group_season_id = group_score.group_season_id
   SET group_score.score = aggregate.total_score
     , group_score.score_reached_at = aggregate.reached_at
     , group_score.updated_at = aggregate.reached_at
     , group_score.version = group_score.version + 1
 WHERE group_score.version = 0;

UPDATE group_creatures AS creature
JOIN (
    SELECT completion.group_season_id
         , SUM(assignment.creature_experience_snapshot) AS total_experience
         , MAX(completion.completed_at) AS reached_at
      FROM quest_completions AS completion
      JOIN user_profiles AS profile
        ON profile.user_id = completion.user_id
       AND profile.normalized_nickname LIKE 'mt93_user_%'
      JOIN quest_assignments AS assignment
        ON assignment.id = completion.quest_assignment_id
     GROUP BY completion.group_season_id
) AS aggregate
  ON aggregate.group_season_id = creature.group_season_id
   SET creature.total_experience = aggregate.total_experience
     , creature.growth_stage = CASE
           WHEN creature.growth_stage = 'EGG' THEN 'EGG'
           WHEN aggregate.total_experience >= 150000 THEN 'COMPLETE'
           WHEN aggregate.total_experience >= 97500 THEN 'MATURE'
           WHEN aggregate.total_experience >= 45000 THEN 'GROWING'
           ELSE 'HATCHLING'
       END
     , creature.growth_stage_reached_at = CASE
           WHEN creature.growth_stage = 'EGG' THEN creature.growth_stage_reached_at
           ELSE aggregate.reached_at
       END
     , creature.updated_at = aggregate.reached_at
     , creature.version = creature.version + 1
 WHERE creature.version = 0;

INSERT INTO user_activity_events (
    user_id
  , event_type
  , source_type
  , source_id
  , membership_id
  , group_season_id
  , business_date
  , occurred_at
  , deduplication_key
  , metadata
  , created_at
)
SELECT completion.user_id
     , 'QUEST_COMPLETED'
     , 'QUEST_COMPLETION'
     , completion.id
     , completion.membership_id
     , completion.group_season_id
     , DATE(completion.completed_at + INTERVAL 9 HOUR)
     , completion.completed_at
     , CONCAT('MT93:QUEST:', completion.id)
     , JSON_OBJECT('seed', 'issue-93')
     , completion.completed_at
  FROM quest_completions AS completion
  JOIN user_profiles AS profile
    ON profile.user_id = completion.user_id
   AND profile.normalized_nickname LIKE 'mt93_user_%'
 WHERE NOT EXISTS (
       SELECT 1
         FROM user_activity_events AS existing
        WHERE existing.deduplication_key = CONCAT('MT93:QUEST:', completion.id)
 );

INSERT INTO user_daily_activities (
    user_id
  , membership_id
  , group_id_snapshot
  , business_date
  , first_active_at
  , last_active_at
  , created_at
  , updated_at
)
SELECT event.user_id
     , event.membership_id
     , membership.group_id
     , event.business_date
     , MIN(event.occurred_at)
     , MAX(event.occurred_at)
     , MIN(event.occurred_at)
     , MAX(event.occurred_at)
  FROM user_activity_events AS event
  JOIN group_memberships AS membership
    ON membership.id = event.membership_id
 WHERE event.deduplication_key LIKE 'MT93:QUEST:%'
   AND NOT EXISTS (
       SELECT 1
         FROM user_daily_activities AS existing
        WHERE existing.user_id = event.user_id
          AND existing.business_date = event.business_date
          AND existing.membership_id = event.membership_id
   )
 GROUP BY event.user_id, event.membership_id, membership.group_id, event.business_date;

-- ============================================================
-- 구매 120건, 결제 거래, 보유 아이템, 환불 40건
-- ============================================================

INSERT INTO purchases (
    order_number
  , user_id
  , group_id_snapshot
  , shop_item_id
  , item_code_snapshot
  , item_name_snapshot
  , slot_code_snapshot
  , price_krw_snapshot
  , currency
  , status
  , version
  , created_at
  , updated_at
)
SELECT CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
     , profile.user_id
     , active_membership.group_id
     , item.id
     , item.code
     , item.name
     , item.slot_code
     , item.price_krw
     , 'KRW'
     , CASE
           WHEN numbers.n <= 70 THEN 'PAID'
           WHEN numbers.n <= 90 THEN 'FAILED'
           WHEN numbers.n <= 110 THEN 'READY'
           ELSE 'CANCELLED'
       END
     , 0
     , @mt93_now - INTERVAL (
           CASE
               WHEN numbers.n BETWEEN 1 AND 30 OR numbers.n BETWEEN 111 AND 120
                   THEN GREATEST(MOD(numbers.n, 30), 3)
               ELSE MOD(numbers.n, 30)
           END
       ) DAY
     , CASE
           WHEN numbers.n > 110 THEN
               @mt93_now - INTERVAL GREATEST(MOD(numbers.n, 30), 3) DAY + INTERVAL 3 DAY
           ELSE @mt93_now - INTERVAL MOD(numbers.n, 30) DAY
       END
  FROM mt93_numbers AS numbers
  JOIN user_profiles AS profile
    ON profile.normalized_nickname =
       CONCAT('mt93_user_', LPAD(MOD(numbers.n - 1, 60) + 1, 3, '0'))
  JOIN shop_items AS item
    ON item.code = CASE WHEN MOD(numbers.n, 2) = 0 THEN 'HAT_LEAF' ELSE 'BODY_CAPE' END
  LEFT JOIN group_memberships AS active_membership
    ON active_membership.user_id = profile.user_id
   AND active_membership.status = 'ACTIVE'
 WHERE numbers.n <= 120
   AND NOT EXISTS (
       SELECT 1
         FROM purchases AS existing
        WHERE existing.order_number =
              CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
   );

INSERT INTO payment_transactions (
    purchase_id
  , provider
  , provider_transaction_id
  , transaction_type
  , amount_krw
  , status
  , provider_result_code
  , processed_at
  , created_at
  , updated_at
)
SELECT purchase.id
     , 'PORTONE'
     , CASE
           WHEN numbers.n <= 70 OR numbers.n > 110
               THEN CONCAT('MT93-APPROVAL-', LPAD(numbers.n, 3, '0'))
           ELSE NULL
       END
     , 'APPROVAL'
     , purchase.price_krw_snapshot
     , CASE
           WHEN numbers.n <= 70 OR numbers.n > 110 THEN 'SUCCESS'
           ELSE 'DEFINITIVE_FAILURE'
       END
     , CASE WHEN numbers.n BETWEEN 71 AND 90 THEN 'DECLINED' ELSE 'OK' END
     , purchase.created_at
     , purchase.created_at
     , purchase.updated_at
  FROM mt93_numbers AS numbers
  JOIN purchases AS purchase
    ON purchase.order_number =
       CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
 WHERE (numbers.n <= 90 OR numbers.n > 110)
   AND numbers.n <= 120
   AND NOT EXISTS (
       SELECT 1
         FROM payment_transactions AS existing
        WHERE existing.purchase_id = purchase.id
          AND existing.transaction_type = 'APPROVAL'
   );

INSERT INTO payment_transactions (
    purchase_id
  , provider
  , provider_transaction_id
  , transaction_type
  , amount_krw
  , status
  , provider_result_code
  , processed_at
  , created_at
  , updated_at
)
SELECT purchase.id
     , 'PORTONE'
     , CONCAT('MT93-CANCEL-', LPAD(numbers.n, 3, '0'))
     , 'CANCELLATION'
     , purchase.price_krw_snapshot
     , 'SUCCESS'
     , 'OK'
     , purchase.updated_at
     , purchase.updated_at
     , purchase.updated_at
  FROM mt93_numbers AS numbers
  JOIN purchases AS purchase
    ON purchase.order_number =
       CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
 WHERE numbers.n BETWEEN 111 AND 120
   AND NOT EXISTS (
       SELECT 1
         FROM payment_transactions AS existing
        WHERE existing.purchase_id = purchase.id
          AND existing.transaction_type = 'CANCELLATION'
   );

INSERT INTO owned_items (
    purchase_id
  , user_id
  , shop_item_id
  , status
  , used_at
  , revoked_at
  , version
  , created_at
  , updated_at
)
SELECT purchase.id
     , purchase.user_id
     , purchase.shop_item_id
     , CASE
           WHEN numbers.n BETWEEN 31 AND 50 THEN 'OWNED_USED'
           WHEN numbers.n > 110 THEN 'REVOKED'
           ELSE 'OWNED_UNUSED'
       END
     , CASE WHEN numbers.n BETWEEN 31 AND 50 THEN purchase.created_at + INTERVAL 1 HOUR ELSE NULL END
     , CASE WHEN numbers.n > 110 THEN purchase.updated_at ELSE NULL END
     , 0
     , purchase.created_at
     , purchase.updated_at
  FROM mt93_numbers AS numbers
  JOIN purchases AS purchase
    ON purchase.order_number =
       CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
 WHERE (numbers.n <= 70 OR numbers.n > 110)
   AND numbers.n <= 120
   AND NOT EXISTS (
       SELECT 1
         FROM owned_items AS existing
        WHERE existing.purchase_id = purchase.id
   );

INSERT INTO avatar_equipments (
    user_id
  , slot_code
  , owned_item_id
  , version
  , created_at
  , updated_at
)
SELECT purchase.user_id
     , purchase.slot_code_snapshot
     , owned.id
     , 0
     , owned.created_at
     , owned.updated_at
  FROM mt93_numbers AS numbers
  JOIN purchases AS purchase
    ON purchase.order_number =
       CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
  JOIN owned_items AS owned
    ON owned.purchase_id = purchase.id
 WHERE numbers.n BETWEEN 31 AND 50
   AND NOT EXISTS (
       SELECT 1
         FROM avatar_equipments AS existing
        WHERE existing.user_id = purchase.user_id
          AND existing.slot_code = purchase.slot_code_snapshot
   );

INSERT INTO refund_requests (
    purchase_id
  , requested_by_user_id
  , reviewed_by_user_id
  , request_reason
  , decision_reason
  , status
  , requested_at
  , reviewed_at
  , cancel_claimed_at
  , completed_at
  , version
  , created_at
  , updated_at
)
SELECT purchase.id
     , purchase.user_id
     , CASE WHEN numbers.n <= 10 THEN NULL ELSE historical_admin.user_id END
     , CONCAT('MT93 환불 요청 ', LPAD(numbers.n, 3, '0'))
     , CASE
           WHEN numbers.n <= 10 THEN NULL
           WHEN numbers.n <= 20 THEN '정책에 따라 환불 거절'
           WHEN numbers.n <= 30 THEN 'PG 명확한 실패'
           ELSE 'PG 취소 완료'
       END
     , CASE
           WHEN numbers.n <= 10 THEN 'REQUESTED'
           WHEN numbers.n <= 20 THEN 'REJECTED'
           WHEN numbers.n <= 30 THEN 'FAILED'
           ELSE 'COMPLETED'
       END
     , purchase.created_at + INTERVAL 1 DAY
     , CASE WHEN numbers.n <= 10 THEN NULL ELSE purchase.created_at + INTERVAL 2 DAY END
     , CASE WHEN numbers.n > 20 THEN purchase.created_at + INTERVAL 2 DAY ELSE NULL END
     , CASE WHEN numbers.n > 110 THEN purchase.created_at + INTERVAL 3 DAY ELSE NULL END
     , 0
     , purchase.created_at + INTERVAL 1 DAY
     , CASE
           WHEN numbers.n <= 10 THEN purchase.created_at + INTERVAL 1 DAY
           ELSE purchase.created_at + INTERVAL 3 DAY
       END
  FROM mt93_numbers AS numbers
  JOIN purchases AS purchase
    ON purchase.order_number =
       CONCAT('93000000-0000-0000-0000-', LPAD(numbers.n, 12, '0'))
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
 WHERE (numbers.n BETWEEN 1 AND 30 OR numbers.n BETWEEN 111 AND 120)
   AND NOT EXISTS (
       SELECT 1
         FROM refund_requests AS existing
        WHERE existing.purchase_id = purchase.id
          AND existing.request_reason = CONCAT('MT93 환불 요청 ', LPAD(numbers.n, 3, '0'))
   );

-- ============================================================
-- 신고 30건과 관리자·강퇴 이력
-- ============================================================

INSERT INTO user_reports (
    reporter_user_id
  , reported_user_id
  , group_id
  , category
  , content
  , status
  , handled_by_user_id
  , handled_at
  , resolution
  , created_at
  , updated_at
)
SELECT reporter.user_id
     , reported.user_id
     , seed_group.id
     , CASE MOD(numbers.n, 4)
           WHEN 0 THEN 'HARASSMENT'
           WHEN 1 THEN 'SPAM'
           WHEN 2 THEN 'INAPPROPRIATE_CONTENT'
           ELSE 'OTHER'
       END
     , CONCAT('MT93 신고 시나리오 ', LPAD(numbers.n, 2, '0'))
     , CASE
           WHEN numbers.n <= 10 THEN 'RECEIVED'
           WHEN numbers.n <= 20 THEN 'RESOLVED'
           ELSE 'REJECTED'
       END
     , CASE WHEN numbers.n <= 10 THEN NULL ELSE historical_admin.user_id END
     , CASE
           WHEN numbers.n <= 10 THEN NULL
           WHEN numbers.n <= 15
               THEN @mt93_history_anchor - INTERVAL (50 - numbers.n) DAY
           ELSE @mt93_history_anchor - INTERVAL (30 - numbers.n) DAY
       END
     , CASE
           WHEN numbers.n <= 10 THEN NULL
           WHEN numbers.n <= 20 THEN '신고 검토 및 조치 완료'
           ELSE '증거 부족으로 기각'
       END
     , CASE
           WHEN numbers.n <= 10
               THEN @mt93_history_anchor - INTERVAL (20 - numbers.n) DAY
           WHEN numbers.n <= 15
               THEN @mt93_history_anchor - INTERVAL (51 - numbers.n) DAY
           ELSE @mt93_history_anchor - INTERVAL (35 - numbers.n) DAY
       END
     , CASE
           WHEN numbers.n <= 10
               THEN @mt93_history_anchor - INTERVAL (20 - numbers.n) DAY
           WHEN numbers.n <= 15
               THEN @mt93_history_anchor - INTERVAL (50 - numbers.n) DAY
           ELSE @mt93_history_anchor - INTERVAL (30 - numbers.n) DAY
       END
  FROM mt93_numbers AS numbers
  JOIN user_profiles AS reporter
    ON reporter.normalized_nickname = 'mt93_user_001'
  JOIN user_profiles AS reported
    ON reported.normalized_nickname =
       CONCAT(
           'mt93_user_',
           LPAD(
               CASE
                   WHEN numbers.n BETWEEN 11 AND 15 THEN numbers.n - 9
                   ELSE MOD(numbers.n - 1, 18) + 2
               END,
               3,
               '0'
           )
       )
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
  JOIN game_groups AS seed_group
    ON seed_group.normalized_name = 'mt93_group_01'
 WHERE numbers.n <= 30
   AND NOT EXISTS (
       SELECT 1
         FROM user_reports AS existing
        WHERE existing.content = CONCAT('MT93 신고 시나리오 ', LPAD(numbers.n, 2, '0'))
   );

INSERT INTO moderation_actions (
    report_id
  , group_id
  , membership_id
  , target_user_id
  , actor_user_id
  , action_type
  , reason
  , executed_at
  , created_at
)
SELECT report.id
     , report.group_id
     , ended_membership.id
     , report.reported_user_id
     , historical_admin.user_id
     , 'REMOVE_REPORTED'
     , CONCAT('MT93 신고 강퇴 ', LPAD(numbers.n, 2, '0'))
     , ended_membership.ended_at
     , ended_membership.ended_at
  FROM mt93_numbers AS numbers
  JOIN user_reports AS report
    ON report.content = CONCAT('MT93 신고 시나리오 ', LPAD(numbers.n + 10, 2, '0'))
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
  JOIN group_memberships AS ended_membership
    ON ended_membership.id = (
       SELECT candidate.id
         FROM group_memberships AS candidate
        WHERE candidate.group_id = report.group_id
          AND candidate.user_id = report.reported_user_id
          AND candidate.status = 'REMOVED'
          AND candidate.exit_reason = 'REPORTED'
        ORDER BY candidate.ended_at DESC
        LIMIT 1
    )
 WHERE numbers.n <= 5
   AND NOT EXISTS (
       SELECT 1
         FROM moderation_actions AS existing
        WHERE existing.report_id = report.id
          AND existing.action_type = 'REMOVE_REPORTED'
   );

INSERT INTO moderation_actions (
    report_id
  , group_id
  , membership_id
  , target_user_id
  , actor_user_id
  , action_type
  , reason
  , executed_at
  , created_at
)
SELECT NULL
     , membership.group_id
     , membership.id
     , membership.user_id
     , historical_admin.user_id
     , 'REMOVE_INACTIVE'
     , CONCAT('MT93 비활동 강퇴 ', membership.id)
     , membership.ended_at
     , membership.ended_at
  FROM (
       SELECT candidate.id
            , candidate.group_id
            , candidate.user_id
            , candidate.ended_at
         FROM group_memberships AS candidate
         JOIN game_groups AS candidate_group
           ON candidate_group.id = candidate.group_id
          AND candidate_group.normalized_name LIKE 'mt93_group_%'
        WHERE candidate.status = 'REMOVED'
          AND candidate.exit_reason = 'INACTIVE'
        ORDER BY candidate.id
        LIMIT 5
       ) AS membership
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
 WHERE NOT EXISTS (
       SELECT 1
         FROM moderation_actions AS existing
        WHERE existing.membership_id = membership.id
          AND existing.action_type = 'REMOVE_INACTIVE'
   );

INSERT INTO admin_audit_logs (
    actor_user_id
  , actor_role_snapshot
  , action_type
  , target_type
  , target_id
  , group_id
  , result
  , reason
  , detail
  , created_at
)
SELECT historical_admin.user_id
     , 'SUPER_ADMIN'
     , 'RESOLVE_REPORT'
     , 'USER_REPORT'
     , report.id
     , report.group_id
     , 'SUCCESS'
     , CONCAT('MT93 신고 감사 ', LPAD(numbers.n, 2, '0'))
     , JSON_OBJECT('seed', 'issue-93', 'reportId', report.id)
     , report.handled_at
  FROM mt93_numbers AS numbers
  JOIN user_reports AS report
    ON report.content = CONCAT('MT93 신고 시나리오 ', LPAD(numbers.n + 10, 2, '0'))
  JOIN user_profiles AS historical_admin
    ON historical_admin.normalized_nickname = 'mt93_user_060'
 WHERE numbers.n <= 10
   AND NOT EXISTS (
       SELECT 1
         FROM admin_audit_logs AS existing
        WHERE existing.reason = CONCAT('MT93 신고 감사 ', LPAD(numbers.n, 2, '0'))
   );

-- ============================================================
-- 실제 활성 상태에서 종료된 시즌의 누락 랭킹 Snapshot
-- ============================================================

INSERT INTO season_group_rank_snapshots (
    season_id
  , group_season_id
  , group_id
  , rank_number
  , score
  , score_reached_at
  , group_name_snapshot
  , created_at
)
SELECT ranked.season_id
     , ranked.group_season_id
     , ranked.group_id
     , ranked.rank_number
     , ranked.score
     , ranked.score_reached_at
     , ranked.group_name
     , ranked.snapshot_at
  FROM (
       SELECT season.id AS season_id
            , group_season.id AS group_season_id
            , seed_group.id AS group_id
            , ROW_NUMBER() OVER (
                  PARTITION BY season.id
                  ORDER BY group_score.score DESC
                         , COALESCE(group_score.score_reached_at, group_season.created_at)
                         , seed_group.id
              ) AS rank_number
            , group_score.score
            , COALESCE(group_score.score_reached_at, group_season.created_at) AS score_reached_at
            , seed_group.name AS group_name
            , seed_group.normalized_name
            , season.updated_at AS snapshot_at
         FROM seasons AS season
         JOIN group_seasons AS group_season
           ON group_season.season_id = season.id
         JOIN game_groups AS seed_group
           ON seed_group.id = group_season.group_id
         JOIN season_group_scores AS group_score
           ON group_score.group_season_id = group_season.id
        WHERE season.status = 'ENDED'
          AND season.closing_from_status = 'ACTIVE'
       ) AS ranked
 WHERE ranked.normalized_name LIKE 'mt93_group_%'
   AND NOT EXISTS (
       SELECT 1
         FROM season_group_rank_snapshots AS existing
        WHERE existing.season_id = ranked.season_id
          AND existing.group_id = ranked.group_id
   );

INSERT INTO season_member_rank_snapshots (
    season_id
  , group_season_id
  , group_id
  , user_id
  , rank_number
  , contribution_score
  , score_reached_at
  , participant_status
  , nickname_snapshot
  , created_at
)
SELECT ranked.season_id
     , ranked.group_season_id
     , ranked.group_id
     , ranked.user_id
     , ranked.rank_number
     , ranked.contribution_score
     , ranked.score_reached_at
     , ranked.participant_status
     , ranked.nickname
     , ranked.snapshot_at
  FROM (
       SELECT season.id AS season_id
            , group_season.id AS group_season_id
            , group_season.group_id
            , contribution.user_id
            , ROW_NUMBER() OVER (
                  PARTITION BY group_season.id
                  ORDER BY contribution.contribution_score DESC
                         , COALESCE(contribution.score_reached_at, contribution.created_at)
                         , contribution.user_id
              ) AS rank_number
            , contribution.contribution_score
            , COALESCE(contribution.score_reached_at, contribution.created_at) AS score_reached_at
            , contribution.participant_status
            , profile.nickname
            , seed_group.normalized_name
            , season.updated_at AS snapshot_at
         FROM seasons AS season
         JOIN group_seasons AS group_season
           ON group_season.season_id = season.id
         JOIN game_groups AS seed_group
           ON seed_group.id = group_season.group_id
         JOIN season_member_contributions AS contribution
           ON contribution.group_season_id = group_season.id
         JOIN user_profiles AS profile
           ON profile.user_id = contribution.user_id
        WHERE season.status = 'ENDED'
          AND season.closing_from_status = 'ACTIVE'
       ) AS ranked
 WHERE ranked.normalized_name LIKE 'mt93_group_%'
   AND NOT EXISTS (
       SELECT 1
         FROM season_member_rank_snapshots AS existing
        WHERE existing.group_season_id = ranked.group_season_id
          AND existing.user_id = ranked.user_id
   );

DROP TEMPORARY TABLE IF EXISTS mt93_completion_plan;
DROP TEMPORARY TABLE IF EXISTS mt93_assignment_plan;
DROP TEMPORARY TABLE IF EXISTS mt93_group_plan;
DROP TEMPORARY TABLE IF EXISTS mt93_numbers;
