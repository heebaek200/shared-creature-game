-- 4-J-1 Scheduler 공통 실행 이력 진단 (SELECT 전용)
-- 주의: 반드시 테스트 전용 MySQL DB에 연결해 실행하세요.
-- 원장/결제/환불/사진 데이터는 변경하지 않습니다.

-- 1. #24에서 사용하는 작업별 실행 상태 분포
SELECT job_type
     , status
     , COUNT(*) AS execution_count
  FROM scheduler_executions
 WHERE job_type IN (
       'PHOTO_RETENTION_CLEANUP'
     , 'PAYMENT_WEBHOOK_RECONCILIATION'
     , 'REFUND_RECONCILIATION'
 )
 GROUP BY job_type, status
 ORDER BY job_type, status;

-- 2. 논리 키 중복은 0건이어야 함
SELECT job_type
     , scheduled_for
     , COUNT(*) AS duplicate_count
  FROM scheduler_executions
 GROUP BY job_type, scheduled_for
HAVING COUNT(*) > 1;

-- 3. 실행권 만료 후에도 남은 RUNNING 상태
SELECT id
     , job_type
     , scheduled_for
     , attempt_count
     , lease_expires_at
  FROM scheduler_executions
 WHERE status = 'RUNNING'
   AND lease_expires_at <= UTC_TIMESTAMP(6)
 ORDER BY lease_expires_at, id;

-- 4. 결제 Webhook 성공 실행의 집계 정합성
SELECT id
     , scheduled_for
     , JSON_EXTRACT(result_summary, '$.selectedCount') AS selected_count
     , JSON_EXTRACT(result_summary, '$.processedCount') AS processed_count
     , JSON_EXTRACT(result_summary, '$.skippedCount') AS skipped_count
     , JSON_EXTRACT(result_summary, '$.failedCount') AS failed_count
  FROM scheduler_executions
 WHERE job_type = 'PAYMENT_WEBHOOK_RECONCILIATION'
   AND status = 'SUCCEEDED'
 ORDER BY scheduled_for DESC
 LIMIT 20;

-- 5. 환불 복구 실행 계층의 성공 요약 (실제 PG 확정 증거는 아님)
SELECT id
     , scheduled_for
     , JSON_EXTRACT(result_summary, '$.completedCount') AS completed_count
     , JSON_EXTRACT(result_summary, '$.definitiveFailedCount') AS definitive_failed_count
     , JSON_EXTRACT(result_summary, '$.errorCount') AS error_count
  FROM scheduler_executions
 WHERE job_type = 'REFUND_RECONCILIATION'
   AND status = 'SUCCEEDED'
 ORDER BY scheduled_for DESC
 LIMIT 20;

-- 6. 실패 이력 확인 (실행 이력의 원인·시도 횟수 유지)
SELECT id
     , job_type
     , scheduled_for
     , attempt_count
     , error_code
     , error_detail
  FROM scheduler_executions
 WHERE status = 'FAILED'
   AND job_type IN (
       'PHOTO_RETENTION_CLEANUP'
     , 'PAYMENT_WEBHOOK_RECONCILIATION'
     , 'REFUND_RECONCILIATION'
 )
 ORDER BY updated_at DESC
 LIMIT 20;
