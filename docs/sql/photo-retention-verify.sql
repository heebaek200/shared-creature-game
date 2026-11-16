-- 4-H-1 사진 보존 상태 조회 전용 SQL (데이터 변경 없음)
-- 주의: MySQL 세션 타임존이 UTC가 아니면 비교 시각에 유의하세요.
-- 프로젝트 DB의 DATETIME(6)은 UTC Instant 값으로 저장됩니다.

-- 1) 사진 제출 이력의 현재 상태 분포
SELECT verification_status
     , COUNT(*) AS row_count
  FROM photo_submissions
 GROUP BY verification_status
 ORDER BY verification_status;

-- 2) 지금 당장 실패 확정 대상인 PENDING (1시간 초과)
SELECT COUNT(*) AS stale_pending_count
  FROM photo_submissions
 WHERE verification_status = 'PENDING'
   AND created_at < UTC_TIMESTAMP(6) - INTERVAL 1 HOUR;

-- 3) 지금 당장 정리 대상인 INVALID (실패 확정 24시간 경과)
SELECT COUNT(*) AS expired_invalid_count
  FROM photo_submissions
 WHERE verification_status = 'INVALID'
   AND file_deleted_at IS NULL
   AND updated_at <= UTC_TIMESTAMP(6) - INTERVAL 24 HOUR;

-- 4) 지금 당장 정리 대상인 VALID (시즌 ENDED + 종료 후 30일)
SELECT COUNT(*) AS expired_valid_count
  FROM photo_submissions AS ps
  JOIN quest_assignments AS qa ON qa.id = ps.quest_assignment_id
  JOIN quest_periods AS qp ON qp.id = qa.quest_period_id
  JOIN seasons AS s ON s.id = qp.season_id
 WHERE ps.verification_status = 'VALID'
   AND ps.file_deleted_at IS NULL
   AND s.status = 'ENDED'
   AND s.end_at <= UTC_TIMESTAMP(6) - INTERVAL 30 DAY;

-- 5) 파일 삭제 이력의 DB 정합성: 삭제 완료 시 파일 키/좌표가 남지 않아야 함
SELECT COUNT(*) AS inconsistent_deleted_rows
  FROM photo_submissions
 WHERE file_deleted_at IS NOT NULL
   AND (storage_key IS NOT NULL OR latitude IS NOT NULL OR longitude IS NOT NULL);
