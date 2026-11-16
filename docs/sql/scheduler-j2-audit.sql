-- STEP 4-J-2: SELECT 전용, 최신 main 병합 이후에도 실행 가능해야 하는 조회
-- DATETIME(6)을 UTC Instant로 저장하는 프로젝트 설정을 기준으로 비교합니다.
-- TEST DB 또는 로컬 개발 DB에서 확인하되 운영 데이터는 변경하지 않습니다.

-- 1. #24 Scheduler Job Type별 실행 현황
SELECT job_type
     , status
     , COUNT(*) AS executions
     , MAX(scheduled_for) AS latest_scheduled_for
  FROM scheduler_executions
 GROUP BY job_type, status
 ORDER BY job_type, status;

-- 2. 동일 Job Type·논리 시각의 중복 실행 행: 0건이어야 함
SELECT job_type
     , scheduled_for
     , COUNT(*) AS duplicate_count
  FROM scheduler_executions
 GROUP BY job_type, scheduled_for
HAVING COUNT(*) > 1;

-- 3. Webhook 상태별 분포
SELECT processing_status
     , COUNT(*) AS event_count
  FROM payment_webhook_events
 GROUP BY processing_status
 ORDER BY processing_status;

-- 4. 정확히 5분 이상 경과한 FAILED/PENDING 결제 Webhook 복구 후보
SELECT event_type
     , processing_status
     , COUNT(*) AS recoverable_count
  FROM payment_webhook_events
 WHERE event_type IN (
           'Transaction.Ready'
         , 'Transaction.Paid'
         , 'Transaction.Failed'
         , 'Transaction.PayPending'
       )
   AND (
           (processing_status = 'FAILED' AND updated_at <= UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE)
        OR (processing_status = 'PENDING' AND processing_started_at <= UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE)
       )
 GROUP BY event_type, processing_status
 ORDER BY event_type, processing_status;

-- 5. 아직 5분이 지나지 않은 FAILED Webhook: 복구 대상이어서는 안 됨
SELECT COUNT(*) AS failed_cooldown_waiting
  FROM payment_webhook_events
 WHERE event_type IN (
           'Transaction.Ready'
         , 'Transaction.Paid'
         , 'Transaction.Failed'
         , 'Transaction.PayPending'
       )
   AND processing_status = 'FAILED'
   AND updated_at > UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE;

-- 6. #24 작업의 실패 이력: 장애 복구 후에도 삭제하지 않음
SELECT job_type
     , scheduled_for
     , status
     , attempt_count
     , error_code
     , error_detail
  FROM scheduler_executions
 WHERE status IN ('FAILED', 'RUNNING')
 ORDER BY updated_at DESC
 LIMIT 50;

-- 7. 사진 삭제 완료 후 저장 키·GPS 좌표 잔존 여부: 0건이어야 함
SELECT COUNT(*) AS inconsistent_photo_rows
  FROM photo_submissions
 WHERE file_deleted_at IS NOT NULL
   AND (storage_key IS NOT NULL OR latitude IS NOT NULL OR longitude IS NOT NULL);
