-- ddl-auto=update가 완화하지 않는 기존 로컬 스키마의 NULL 허용 여부와 JSON 형식을 보정합니다.
-- Backend를 중지하고 백업을 확인한 뒤 기존 개발 DB에 한 번만 실행합니다.

ALTER TABLE owned_items
    MODIFY used_at    DATETIME(6) NULL
  , MODIFY revoked_at DATETIME(6) NULL;

ALTER TABLE avatar_equipments
    MODIFY owned_item_id BIGINT NULL;

ALTER TABLE refund_requests
    MODIFY reviewed_by_user_id BIGINT       NULL
  , MODIFY decision_reason     VARCHAR(500) NULL
  , MODIFY reviewed_at         DATETIME(6)  NULL
  , MODIFY cancel_claimed_at   DATETIME(6)  NULL
  , MODIFY completed_at        DATETIME(6)  NULL;

ALTER TABLE payment_transactions
    MODIFY provider_transaction_id VARCHAR(255) NULL
  , MODIFY provider_result_code    VARCHAR(100) NULL
  , MODIFY processed_at            DATETIME(6)  NULL;

ALTER TABLE payment_webhook_events
    MODIFY provider_payment_id   VARCHAR(100)  NULL
  , MODIFY purchase_id           BIGINT        NULL
  , MODIFY failure_detail        VARCHAR(1000) NULL
  , MODIFY processing_started_at DATETIME(6)   NULL
  , MODIFY processed_at          DATETIME(6)   NULL
  , MODIFY payload_summary       JSON          NOT NULL;

ALTER TABLE pg_api_attempts
    MODIFY http_status          INT          NULL
  , MODIFY provider_result_code VARCHAR(100) NULL
  , MODIFY response_summary     JSON         NULL
  , MODIFY completed_at         DATETIME(6)  NULL;

ALTER TABLE api_idempotency_records
    MODIFY http_status       SMALLINT    NULL
  , MODIFY resource_type     VARCHAR(50) NULL
  , MODIFY resource_id       BIGINT      NULL
  , MODIFY response_snapshot JSON        NULL
  , MODIFY completed_at      DATETIME(6) NULL;

ALTER TABLE scheduler_executions
    MODIFY completed_at   DATETIME(6)   NULL
  , MODIFY result_summary JSON          NULL
  , MODIFY error_code     VARCHAR(100)  NULL
  , MODIFY error_detail   VARCHAR(1000) NULL;

ALTER TABLE quest_periods
    MODIFY failure_code   VARCHAR(50)   NULL
  , MODIFY failure_detail VARCHAR(1000) NULL;

ALTER TABLE photo_submissions
    MODIFY storage_key     VARCHAR(500)   NULL
  , MODIFY captured_at     DATETIME(6)    NULL
  , MODIFY latitude        DECIMAL(10, 7) NULL
  , MODIFY longitude       DECIMAL(10, 7) NULL
  , MODIFY failure_reason  VARCHAR(50)    NULL
  , MODIFY file_deleted_at DATETIME(6)    NULL;

ALTER TABLE user_activity_events
    MODIFY deduplication_key VARCHAR(128) NULL
  , MODIFY metadata          JSON         NULL;

ALTER TABLE user_reports
    MODIFY handled_by_user_id BIGINT        NULL
  , MODIFY handled_at         DATETIME(6)   NULL
  , MODIFY resolution         VARCHAR(1000) NULL;

ALTER TABLE quest_template_versions
    MODIFY verification_parameters JSON NOT NULL;

ALTER TABLE quest_assignments
    MODIFY verification_parameters_snapshot JSON NOT NULL;

ALTER TABLE admin_audit_logs
    MODIFY detail JSON NOT NULL;
