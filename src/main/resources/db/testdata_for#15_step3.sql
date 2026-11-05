-- to dev: 이 테스트용 SQL은 소셜 회원가입 및 첫 그룹 생성 성공 전에 실행하면 안됩니다.

-- ============================================================
-- 그룹 관리자 기능 수동 테스트용 회원
-- 본인이 생성한 ACTIVE 그룹이 존재할 때만 생성합니다.
-- 재실행 시 중복 회원과 멤버십을 생성하지 않습니다.
-- ============================================================

-- 1. 테스트 데이터를 삽입할 활성 그룹을 지정합니다.
SET @seed_group_id = (
    SELECT id
      FROM game_groups
     WHERE id = 1
       AND status = 'ACTIVE'
);

-- 2-A. 사용자 생성
INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
)
SELECT
    'ACTIVE'
     , 'USER'
     , UTC_TIMESTAMP(6)
     , UTC_TIMESTAMP(6)
     , 0
     , UTC_TIMESTAMP(6)
     , UTC_TIMESTAMP(6)
FROM DUAL
WHERE @seed_group_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM user_profiles
    WHERE normalized_nickname = 'dev_step3_a'
);

-- 방금 생성한 ID 또는 기존 테스트 회원의 ID를 확보합니다.
SET @seed_user_a = IF(
    ROW_COUNT() = 1
  , LAST_INSERT_ID()
  , (
      SELECT user_id
        FROM user_profiles
       WHERE normalized_nickname = 'dev_step3_a'
    )
);

-- 3-A. 프로필 생성
INSERT INTO user_profiles (
                            user_id
                          , nickname
                          , normalized_nickname
                          , version
                          , created_at
                          , updated_at
)
SELECT
    @seed_user_a
     , 'dev_step3_a'
     , 'dev_step3_a'
     , 0
     , UTC_TIMESTAMP(6)
     , UTC_TIMESTAMP(6)
FROM DUAL
WHERE @seed_user_a IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM user_profiles
    WHERE user_id = @seed_user_a
);

-- 2-B. 사용자 생성 (5일간 활동이 없는 회원)
INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
)
SELECT
    'ACTIVE'
     , 'USER'
     , UTC_TIMESTAMP(6) - INTERVAL 5 DAY
     , UTC_TIMESTAMP(6) - INTERVAL 5 DAY
     , 0
     , UTC_TIMESTAMP(6) - INTERVAL 5 DAY
     , UTC_TIMESTAMP(6)
FROM DUAL
WHERE @seed_group_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM user_profiles
    WHERE normalized_nickname = 'dev_step3_b'
    );

SET @seed_user_b = IF(
    ROW_COUNT() = 1
  , LAST_INSERT_ID()
  , (
      SELECT user_id
        FROM user_profiles
       WHERE normalized_nickname = 'dev_step3_b'
    )
);

-- 3-B. 프로필 생성
INSERT INTO user_profiles (
                            user_id
                          , nickname
                          , normalized_nickname
                          , version
                          , created_at
                          , updated_at
)
SELECT
    @seed_user_b
     , 'dev_step3_b'
     , 'dev_step3_b'
     , 0
     , UTC_TIMESTAMP(6)
     , UTC_TIMESTAMP(6)
FROM DUAL
WHERE @seed_user_b IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM user_profiles
    WHERE user_id = @seed_user_b
);

-- 회원 A를 MEMBER로 등록합니다.
INSERT INTO group_memberships (
                                group_id
                              , user_id
                              , role
                              , status
                              , joined_at
                              , version
                              , created_at
                              , updated_at
)
SELECT
    @seed_group_id
     , @seed_user_a
     , 'MEMBER'
     , 'ACTIVE'
     , UTC_TIMESTAMP(6)
     , 0
     , UTC_TIMESTAMP(6)
     , UTC_TIMESTAMP(6)
FROM DUAL
WHERE @seed_group_id IS NOT NULL
  AND @seed_user_a IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM group_memberships
    WHERE user_id = @seed_user_a
)
  AND (
          SELECT COUNT(*)
          FROM group_memberships
          WHERE group_id = @seed_group_id
            AND status = 'ACTIVE'
      ) < (
          SELECT capacity
          FROM game_groups
          WHERE id = @seed_group_id
      );

-- 회원 B를 MEMBER로 등록합니다.
INSERT INTO group_memberships (
                                group_id
                              , user_id
                              , role
                              , status
                              , joined_at
                              , version
                              , created_at
                              , updated_at
)
SELECT
    @seed_group_id
     , @seed_user_b
     , 'MEMBER'
     , 'ACTIVE'
     , UTC_TIMESTAMP(6) - INTERVAL 5 DAY
     , 0
     , UTC_TIMESTAMP(6) - INTERVAL 5 DAY
     , UTC_TIMESTAMP(6)
FROM DUAL
WHERE @seed_group_id IS NOT NULL
  AND @seed_user_b IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM group_memberships
    WHERE user_id = @seed_user_b
    )
  AND (
    SELECT COUNT(*)
    FROM group_memberships
    WHERE group_id = @seed_group_id
  AND status = 'ACTIVE'
    ) < (
    SELECT capacity
    FROM game_groups
    WHERE id = @seed_group_id
    );

-- 실제 활성 멤버십 수와 다를 때만 카운터를 갱신합니다.
UPDATE game_groups g
SET g.current_member_count = (
    SELECT COUNT(*)
    FROM group_memberships gm
    WHERE gm.group_id = g.id
      AND gm.status = 'ACTIVE'
)
  , g.version = g.version + 1
  , g.updated_at = UTC_TIMESTAMP(6)
WHERE g.id = @seed_group_id
  AND g.current_member_count <> (
    SELECT COUNT(*)
    FROM group_memberships gm
    WHERE gm.group_id = g.id
      AND gm.status = 'ACTIVE'
);