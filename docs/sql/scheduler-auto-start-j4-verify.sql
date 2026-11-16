-- STEP 4-J-4 local/dev Scheduler 자동 활성화 검증 (SELECT 전용)
-- 반드시 local/dev에서 연결된 DB를 확인하고 실행하세요.
-- 수동으로 Scheduler 행을 INSERT/UPDATE/DELETE 하지 않습니다.

-- 1. 작업 유형별 최근 실행 및 상태 확인
SELECT job_type
     , status
     , COUNT(*)      AS execution_count
     , MAX(scheduled_for) AS latest_scheduled_for
  FROM scheduler_executions
 WHERE job_type IN (
       'SEASON_CLOSE'
     , 'QUEST_ASSIGN_DAILY'
     , 'QUEST_ASSIGN_WEEKLY'
     , 'PHOTO_RETENTION_CLEANUP'
     , 'PAYMENT_WEBHOOK_RECONCILIATION'
     , 'REFUND_RECONCILIATION'
 )
 GROUP BY job_type, status
 ORDER BY job_type, status;

-- 2. 사진·결제 Webhook의 최근 5분 슬롯 실행 이력
-- 서버 기동 직후의 현재 슬롯과 다음 5분 경계의 슬롯을 비교합니다.
SELECT job_type
     , scheduled_for
     , status
     , attempt_count
     , started_at
     , completed_at
     , heartbeat_at
     , error_code
     , error_detail
  FROM scheduler_executions
 WHERE job_type IN (
       'PHOTO_RETENTION_CLEANUP'
     , 'PAYMENT_WEBHOOK_RECONCILIATION'
 )
 ORDER BY scheduled_for DESC, job_type
 LIMIT 40;

-- 3. 동일 Job Type과 논리 구간의 실행권 중복: 0건이어야 합니다.
SELECT job_type
     , scheduled_for
     , COUNT(*) AS duplicate_count
  FROM scheduler_executions
 GROUP BY job_type, scheduled_for
HAVING COUNT(*) > 1;

-- 4. 점검 중 실패 또는 Lease 만료된 실행 확인
SELECT id
     , job_type
     , scheduled_for
     , status
     , attempt_count
     , lease_expires_at
     , error_code
     , error_detail
  FROM scheduler_executions
 WHERE status = 'FAILED'
    OR (
           status = 'RUNNING'
       AND lease_expires_at <= UTC_TIMESTAMP(6)
    )
 ORDER BY scheduled_for DESC
 LIMIT 50;

-- 5. 시즌 상태 확인: '현재 ACTIVE' 여부는 start_at/end_at과 함께 판단합니다.
SELECT id
     , status
     , start_at
     , end_at
  FROM seasons
 ORDER BY start_at DESC
 LIMIT 10;
