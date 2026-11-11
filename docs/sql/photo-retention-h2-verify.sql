-- STEP 4-H-2 / 조회 전용 검증 SQL
-- DATETIME(6)이 UTC Instant를 보관한다는 프로젝트 규약을 전제로 한다.
-- SELECT 문만 사용하며 사진 파일·DB의 변경은 수행하지 않는다.

-- 1. 현재 사진 판정 상태와 판정 사유
SELECT verification_status
     , failure_reason
     , COUNT(*) AS submission_count
  FROM photo_submissions
 GROUP BY verification_status, failure_reason
 ORDER BY verification_status, failure_reason;

-- 2. 1시간을 초과한 PENDING 잔여 건수 (정확히 1시간은 제외)
SELECT COUNT(*) AS stale_pending_count
  FROM photo_submissions
 WHERE verification_status = 'PENDING'
   AND created_at < UTC_TIMESTAMP(6) - INTERVAL 1 HOUR;

-- 3. 실패 후 24시간이 지난 사진/좌표 미정리 건수
SELECT COUNT(*) AS expired_invalid_count
  FROM photo_submissions
 WHERE verification_status = 'INVALID'
   AND file_deleted_at IS NULL
   AND updated_at <= UTC_TIMESTAMP(6) - INTERVAL 24 HOUR;

-- 4. 종료가 확정되고 30일이 지난 성공 사진의 미정리 건수
SELECT COUNT(*) AS expired_valid_count
  FROM photo_submissions AS ps
  JOIN quest_assignments AS qa ON qa.id = ps.quest_assignment_id
  JOIN quest_periods AS qp ON qp.id = qa.quest_period_id
  JOIN seasons AS s ON s.id = qp.season_id
 WHERE ps.verification_status = 'VALID'
   AND ps.file_deleted_at IS NULL
   AND s.status = 'ENDED'
   AND s.end_at <= UTC_TIMESTAMP(6) - INTERVAL 30 DAY;

-- 5. 파일 삭제 완료 기록과 개인정보 정리 상태의 DB 불일치
SELECT COUNT(*) AS inconsistent_deleted_rows
  FROM photo_submissions
 WHERE file_deleted_at IS NOT NULL
   AND (
         storage_key IS NOT NULL
      OR latitude IS NOT NULL
      OR longitude IS NOT NULL
   );

-- 6. 사진 정리 실행권 최근 결과 (중복 실행/실패 재시도 진단)
SELECT id
     , job_type
     , scheduled_for
     , status
     , attempt_count
     , heartbeat_at
     , lease_expires_at
     , result_summary
     , error_detail
  FROM scheduler_executions
 WHERE job_type = 'PHOTO_RETENTION_CLEANUP'
 ORDER BY scheduled_for DESC
 LIMIT 20;
