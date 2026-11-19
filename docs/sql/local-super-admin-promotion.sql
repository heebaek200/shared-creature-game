-- ============================================================
-- 로컬 개발환경 전용: 기존 사용자 SUPER_ADMIN 권한 승격
-- ============================================================
-- 목적: 관리자 화면 및 API 수동 테스트를 위한 계정 권한 변경
-- 대상: ACTIVE 상태의 일반 사용자(USER)
-- 주의: 자동 실행 금지. 운영 DB에서 사용 금지.
-- 사용: @target_user_id를 확인하고 트랜잭션을 수동 실행
-- ============================================================

-- 전체 관리자 권한을 부여할 사용자 PK
SET @target_user_id = 1;

START TRANSACTION;

-- 변경 대상 확인
SELECT u.id
     , p.nickname
     , u.status
     , u.global_role
  FROM users AS u
  LEFT JOIN user_profiles AS p
    ON p.user_id = u.id
 WHERE u.id = @target_user_id
 FOR UPDATE;

-- 활성 일반 사용자만 전체 관리자로 승격
UPDATE users
   SET global_role = 'SUPER_ADMIN'
     , version     = version + 1
     , updated_at  = UTC_TIMESTAMP(6)
 WHERE id          = @target_user_id
   AND status      = 'ACTIVE'
   AND global_role = 'USER';

SELECT ROW_COUNT() AS affected_rows;

-- 변경 결과 확인
SELECT id
     , status
     , global_role
  FROM users
 WHERE id = @target_user_id;

COMMIT;