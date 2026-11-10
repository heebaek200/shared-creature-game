-- R-3 READ ONLY: Scheduler 성공 상태와 퀘스트 기간·Snapshot 건수를 점검합니다.
-- 현재/과거 전체 실행 이력을 확인하며 DB 데이터는 변경하지 않습니다.
SELECT
    e.job_type
  , e.scheduled_for
  , e.status AS execution_status
  , p.id AS quest_period_id
  , p.status AS period_status
  , COUNT(a.id) AS assignment_count
  , CASE
        WHEN e.status <> 'SUCCEEDED' THEN 'NOT_SUCCEEDED'
        WHEN p.id IS NULL THEN 'PERIOD_MISSING'
        WHEN p.status <> 'ACTIVE' THEN 'PERIOD_NOT_ACTIVE'
        WHEN COUNT(a.id) <> CASE e.job_type
                                WHEN 'QUEST_ASSIGN_DAILY' THEN 3
                                WHEN 'QUEST_ASSIGN_WEEKLY' THEN 7
                                ELSE -1
                            END THEN 'ASSIGNMENT_COUNT_MISMATCH'
        ELSE 'COUNT_OK'
    END AS inspection_result
FROM scheduler_executions AS e
LEFT JOIN quest_periods AS p
       ON p.start_at = e.scheduled_for
      AND p.period_type = CASE e.job_type
                              WHEN 'QUEST_ASSIGN_DAILY' THEN 'DAILY'
                              WHEN 'QUEST_ASSIGN_WEEKLY' THEN 'WEEKLY'
                              ELSE NULL
                          END
LEFT JOIN quest_assignments AS a
       ON a.quest_period_id = p.id
WHERE e.job_type IN ('QUEST_ASSIGN_DAILY', 'QUEST_ASSIGN_WEEKLY')
GROUP BY
    e.id
  , e.job_type
  , e.scheduled_for
  , e.status
  , p.id
  , p.status
ORDER BY e.scheduled_for DESC, e.job_type ASC;

-- R-3 READ ONLY: 슬롯 번호의 누락·비연속과 템플릿 중복을 육안으로 진단합니다.
SELECT
    p.id AS period_id
  , p.period_type
  , p.start_at
  , p.status AS period_status
  , a.slot_number
  , a.quest_template_id
  , a.quest_template_version_id
  , a.title_snapshot
FROM quest_periods AS p
LEFT JOIN quest_assignments AS a
       ON a.quest_period_id = p.id
WHERE p.period_type IN ('DAILY', 'WEEKLY')
ORDER BY p.start_at DESC, p.period_type, a.slot_number;
