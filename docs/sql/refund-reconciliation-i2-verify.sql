-- STEP 4-I-2 환불 Scheduler 상태 진단 (조회 전용)
-- 실제 환불 원장·결제 원장은 UPDATE/DELETE 하지 않습니다.

-- 1. 환불 상태별 원장 분포
SELECT status
     , COUNT(*) AS row_count
  FROM refund_requests
 GROUP BY status
 ORDER BY status;

-- 2. 현재 시각 기준 5분 이상 경과한 환불 진행/불명 대상
--    실제 업무 기준 시각과 조회 조건은 #22에서 확정해야 합니다.
SELECT id
     , status
     , updated_at
  FROM refund_requests
 WHERE status IN ('CANCEL_PENDING', 'CANCEL_UNKNOWN')
   AND updated_at <= UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE
 ORDER BY updated_at, id
 LIMIT 100;

-- 3. Scheduler 실행 이력 확인
SELECT id
     , job_type
     , scheduled_for
     , status
     , attempt_count
     , owner_instance_id
     , heartbeat_at
     , lease_expires_at
     , completed_at
     , result_summary
     , error_code
  FROM scheduler_executions
 WHERE job_type = 'REFUND_RECONCILIATION'
 ORDER BY scheduled_for DESC
 LIMIT 20;

-- 4. 확정되지 않은 환불을 완료로 오판하지 않았는지 수동 점검
SELECT COUNT(*) AS unresolved_refunds
  FROM refund_requests
 WHERE status IN ('CANCEL_PENDING', 'CANCEL_UNKNOWN');
