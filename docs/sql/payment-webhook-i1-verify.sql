-- 4-I-1 결제 Webhook 복구 실행 상태 검증 (조회 전용)
-- MySQL DATETIME(6)에 UTC Instant를 저장한다는 프로젝트 설정을 전제로 합니다.
-- 실제 PortOne API 호출은 수행하지 않습니다.

-- 1. 결제 이벤트 처리 상태별 건수 및 경과 시간
SELECT processing_status
     , event_type
     , COUNT(*) AS event_count
  FROM payment_webhook_events
 GROUP BY processing_status, event_type
 ORDER BY processing_status, event_type;

-- 2. 현재 #21 서비스가 검색하는 복구 후보: 네 가지 결제 이벤트만 포함
--    참고: #21 Repository 구현은 FAILED를 경과시간에 관계없이 검색합니다.
SELECT COUNT(*) AS recoverable_webhook_count
  FROM payment_webhook_events
 WHERE event_type IN (
       'Transaction.Ready'
     , 'Transaction.Paid'
     , 'Transaction.Failed'
     , 'Transaction.PayPending'
 )
   AND (
       processing_status = 'FAILED'
    OR (processing_status = 'PENDING'
       AND processing_started_at <= UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE)
 );

-- 3. 최근 결제 Webhook Scheduler 실행 이력
SELECT id
     , job_type
     , scheduled_for
     , status
     , attempt_count
     , lease_expires_at
     , result_summary
     , error_code
     , error_detail
  FROM scheduler_executions
 WHERE job_type = 'PAYMENT_WEBHOOK_RECONCILIATION'
 ORDER BY scheduled_for DESC
        , id DESC
 LIMIT 20;

-- 4. 결제/취소 이벤트를 혼동하지 않았는지 상태별 분포 확인
SELECT event_type
     , processing_status
     , COUNT(*) AS event_count
  FROM payment_webhook_events
 WHERE event_type IN (
       'Transaction.Cancelled'
     , 'Transaction.PartialCancelled'
     , 'Transaction.CancelPending'
 )
 GROUP BY event_type, processing_status
 ORDER BY event_type, processing_status;
