-- STEP 4-J-3 결제 Webhook Scheduler 실행권 상태 조회 (읽기 전용)
-- 실제 운영 환경에서는 SELECT만 실행하세요.

-- 1. 실행 이력의 시도 횟수·Lease·Heartbeat 비교
SELECT job_type
     , scheduled_for
     , status
     , attempt_count
     , owner_instance_id
     , started_at
     , heartbeat_at
     , lease_expires_at
     , completed_at
     , error_code
     , error_detail
  FROM scheduler_executions
 WHERE job_type = 'PAYMENT_WEBHOOK_RECONCILIATION'
 ORDER BY scheduled_for DESC
 LIMIT 30;

-- 2. 현재 Lease가 만료된 RUNNING 작업
SELECT job_type
     , scheduled_for
     , owner_instance_id
     , lease_expires_at
  FROM scheduler_executions
 WHERE job_type = 'PAYMENT_WEBHOOK_RECONCILIATION'
   AND status = 'RUNNING'
   AND lease_expires_at <= UTC_TIMESTAMP(6);

-- 3. 같은 시각에 여러 실행 인스턴스가 작업 중인지 직접 확인하기 위한 참고 집계
-- 이 SQL만으로 다른 5분 구간의 전역 단일 실행을 보증하지는 않습니다.
SELECT COUNT(*) AS currently_running_buckets
  FROM scheduler_executions
 WHERE job_type = 'PAYMENT_WEBHOOK_RECONCILIATION'
   AND status = 'RUNNING'
   AND lease_expires_at > UTC_TIMESTAMP(6);
