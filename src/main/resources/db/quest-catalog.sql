-- R-2: 확정 카탈로그 28개 + 불변 버전 1을 재실행 가능하게 등록합니다.
-- 출처: docs/business-rules.md 4.3/4.4, docs/api-design.md 퀘스트 보상 규칙.
-- 기존 퀘스트 배정, 원장, 과거 템플릿 버전은 변경하거나 삭제하지 않습니다.
-- 이 파일은 MySQL 8, Spring SQL initialization 전용입니다.

-- 이전 data.sql의 임시 3개는 삭제 대신 비활성화 및 회전 번호 이동 처리.
-- 현재·과거 quest_assignments가 이 템플릿을 참조하는 경우에도 FK가 보존됩니다.
UPDATE quest_templates
   SET status         = 'INACTIVE'
     , rotation_order = 1001
     , updated_at     = UTC_TIMESTAMP(6)
 WHERE code = 'DAILY_WATER'
   AND (status <> 'INACTIVE' OR rotation_order <> 1001);

UPDATE quest_templates
   SET status         = 'INACTIVE'
     , rotation_order = 1002
     , updated_at     = UTC_TIMESTAMP(6)
 WHERE code = 'DAILY_STRETCH'
   AND (status <> 'INACTIVE' OR rotation_order <> 1002);

UPDATE quest_templates
   SET status         = 'INACTIVE'
     , rotation_order = 1001
     , updated_at     = UTC_TIMESTAMP(6)
 WHERE code = 'WEEKLY_WALK'
   AND (status <> 'INACTIVE' OR rotation_order <> 1001);

-- 공식 코드가 없는 경우만 추가합니다. rotation_order UNIQUE 충돌은 그대로 오류를 내어 검토합니다.
-- 관리자가 변경한 공식 템플릿의 활성 상태나 회전 순서는 재기동 시 덮어쓰지 않습니다.
INSERT INTO quest_templates (
    code
  , period_type
  , difficulty
  , status
  , rotation_order
  , created_at
  , updated_at
)
SELECT seed.code
     , seed.period_type
     , seed.difficulty
     , 'ACTIVE'
     , seed.rotation_order
     , UTC_TIMESTAMP(6)
     , UTC_TIMESTAMP(6)
  FROM (
        SELECT 'D-L-01' AS code, 'DAILY' AS period_type, 'LOW' AS difficulty, 1 AS rotation_order
        UNION ALL SELECT 'D-L-02', 'DAILY', 'LOW', 2
        UNION ALL SELECT 'D-L-03', 'DAILY', 'LOW', 3
        UNION ALL SELECT 'D-L-04', 'DAILY', 'LOW', 4
        UNION ALL SELECT 'D-L-05', 'DAILY', 'LOW', 5
        UNION ALL SELECT 'D-M-01', 'DAILY', 'MEDIUM', 1
        UNION ALL SELECT 'D-M-02', 'DAILY', 'MEDIUM', 2
        UNION ALL SELECT 'D-M-03', 'DAILY', 'MEDIUM', 3
        UNION ALL SELECT 'D-M-04', 'DAILY', 'MEDIUM', 4
        UNION ALL SELECT 'D-H-01', 'DAILY', 'HIGH', 1
        UNION ALL SELECT 'D-H-02', 'DAILY', 'HIGH', 2
        UNION ALL SELECT 'D-H-03', 'DAILY', 'HIGH', 3
        UNION ALL SELECT 'D-X-01', 'DAILY', 'MEDIUM', 5
        UNION ALL SELECT 'W-L-01', 'WEEKLY', 'LOW', 1
        UNION ALL SELECT 'W-L-02', 'WEEKLY', 'LOW', 2
        UNION ALL SELECT 'W-L-03', 'WEEKLY', 'LOW', 3
        UNION ALL SELECT 'W-L-04', 'WEEKLY', 'LOW', 4
        UNION ALL SELECT 'W-L-05', 'WEEKLY', 'LOW', 5
        UNION ALL SELECT 'W-L-06', 'WEEKLY', 'LOW', 6
        UNION ALL SELECT 'W-M-01', 'WEEKLY', 'MEDIUM', 1
        UNION ALL SELECT 'W-M-02', 'WEEKLY', 'MEDIUM', 2
        UNION ALL SELECT 'W-M-03', 'WEEKLY', 'MEDIUM', 3
        UNION ALL SELECT 'W-M-04', 'WEEKLY', 'MEDIUM', 4
        UNION ALL SELECT 'W-H-01', 'WEEKLY', 'HIGH', 1
        UNION ALL SELECT 'W-H-02', 'WEEKLY', 'HIGH', 2
        UNION ALL SELECT 'W-H-03', 'WEEKLY', 'HIGH', 3
        UNION ALL SELECT 'W-H-04', 'WEEKLY', 'HIGH', 4
        UNION ALL SELECT 'W-X-01', 'WEEKLY', 'MEDIUM', 5
       ) AS seed
 WHERE NOT EXISTS (
       SELECT 1
         FROM quest_templates AS existing
        WHERE existing.code = seed.code
 );

-- 신규 공식 템플릿의 version_number=1만 삽입합니다.
-- 기존 v1과 관리자가 추가한 v2 이상은 불변으로 유지합니다.
-- title/description은 확정 설계의 활동 문구를 그대로 사용합니다.
INSERT INTO quest_template_versions (
    quest_template_id
  , version_number
  , title
  , description
  , verification_type
  , verification_parameters
  , creature_experience
  , contribution_score
  , created_at
)
SELECT seed.template_id
     , 1
     , seed.title
     , seed.title
     , seed.verification_type
     , CASE
           WHEN seed.target IS NULL THEN JSON_OBJECT()
           ELSE JSON_OBJECT('target', seed.target)
       END
     , CASE seed.period_type WHEN 'DAILY' THEN 100 ELSE 300 END
     , CASE
           WHEN seed.period_type = 'DAILY' AND seed.difficulty = 'LOW' THEN 10
           WHEN seed.period_type = 'DAILY' AND seed.difficulty = 'MEDIUM' THEN 20
           WHEN seed.period_type = 'DAILY' AND seed.difficulty = 'HIGH' THEN 30
           WHEN seed.period_type = 'WEEKLY' AND seed.difficulty = 'LOW' THEN 30
           WHEN seed.period_type = 'WEEKLY' AND seed.difficulty = 'MEDIUM' THEN 60
           WHEN seed.period_type = 'WEEKLY' AND seed.difficulty = 'HIGH' THEN 90
       END
     , UTC_TIMESTAMP(6)
  FROM (
       SELECT t.id AS template_id
            , t.code
            , t.period_type
            , t.difficulty
            , CASE t.code
                  WHEN 'D-L-01' THEN '물 한 잔 마시기'
                  WHEN 'D-L-02' THEN '5분 스트레칭'
                  WHEN 'D-L-03' THEN '주변 5분 정리'
                  WHEN 'D-L-04' THEN '10분 독서'
                  WHEN 'D-L-05' THEN '3분 호흡·휴식'
                  WHEN 'D-M-01' THEN '기상 체크인'
                  WHEN 'D-M-02' THEN '15분 걷기'
                  WHEN 'D-M-03' THEN '25분 집중 활동'
                  WHEN 'D-M-04' THEN '10분 운동'
                  WHEN 'D-H-01' THEN '30분 걷기·운동'
                  WHEN 'D-H-02' THEN '50분 집중 활동'
                  WHEN 'D-H-03' THEN '오늘의 하·중 퀘스트 완료'
                  WHEN 'D-X-01' THEN '야외 위치 사진 인증'
                  WHEN 'W-L-01' THEN '주간 서비스 체크인 3일'
                  WHEN 'W-L-02' THEN '일일 퀘스트 3회 완료'
                  WHEN 'W-L-03' THEN '서로 다른 2일에 일일 퀘스트 완료'
                  WHEN 'W-L-04' THEN '하 난이도 일일 퀘스트 2회 완료'
                  WHEN 'W-L-05' THEN 'SELF_CHECK 일일 퀘스트 2회 완료'
                  WHEN 'W-L-06' THEN '오전 기상 체크인 2일'
                  WHEN 'W-M-01' THEN '서비스 체크인 5일'
                  WHEN 'W-M-02' THEN '일일 퀘스트 7회 완료'
                  WHEN 'W-M-03' THEN '서로 다른 4일에 일일 퀘스트 완료'
                  WHEN 'W-M-04' THEN '중 난이도 일일 퀘스트 3회 완료'
                  WHEN 'W-H-01' THEN '서비스 체크인 7일'
                  WHEN 'W-H-02' THEN '일일 퀘스트 14회 완료'
                  WHEN 'W-H-03' THEN '서로 다른 3일에 하루 일일 퀘스트 3개 전부 완료'
                  WHEN 'W-H-04' THEN '오전 기상 체크인 5일'
                  WHEN 'W-X-01' THEN '서로 다른 2일에 유효한 위치 사진 인증'
              END AS title
            , CASE
                  WHEN t.code = 'D-M-01' THEN 'SERVER_EVENT'
                  WHEN t.code IN ('D-X-01', 'W-X-01') THEN 'PHOTO_EXIF'
                  WHEN t.code = 'D-H-03' OR t.period_type = 'WEEKLY' THEN 'AGGREGATE'
                  ELSE 'SELF_CHECK'
              END AS verification_type
            , CASE t.code
                  WHEN 'D-M-01' THEN 1
                  WHEN 'D-H-03' THEN 2
                  WHEN 'D-X-01' THEN 1
                  WHEN 'W-L-01' THEN 3
                  WHEN 'W-L-02' THEN 3
                  WHEN 'W-L-03' THEN 2
                  WHEN 'W-L-04' THEN 2
                  WHEN 'W-L-05' THEN 2
                  WHEN 'W-L-06' THEN 2
                  WHEN 'W-M-01' THEN 5
                  WHEN 'W-M-02' THEN 7
                  WHEN 'W-M-03' THEN 4
                  WHEN 'W-M-04' THEN 3
                  WHEN 'W-H-01' THEN 7
                  WHEN 'W-H-02' THEN 14
                  WHEN 'W-H-03' THEN 3
                  WHEN 'W-H-04' THEN 5
                  WHEN 'W-X-01' THEN 2
                  ELSE NULL
              END AS target
         FROM quest_templates AS t
        WHERE t.code IN (
              'D-L-01'
            , 'D-L-02'
            , 'D-L-03'
            , 'D-L-04'
            , 'D-L-05'
            , 'D-M-01'
            , 'D-M-02'
            , 'D-M-03'
            , 'D-M-04'
            , 'D-H-01'
            , 'D-H-02'
            , 'D-H-03'
            , 'D-X-01'
            , 'W-L-01'
            , 'W-L-02'
            , 'W-L-03'
            , 'W-L-04'
            , 'W-L-05'
            , 'W-L-06'
            , 'W-M-01'
            , 'W-M-02'
            , 'W-M-03'
            , 'W-M-04'
            , 'W-H-01'
            , 'W-H-02'
            , 'W-H-03'
            , 'W-H-04'
            , 'W-X-01'
        )
       ) AS seed
 WHERE NOT EXISTS (
       SELECT 1
         FROM quest_template_versions AS version
        WHERE version.quest_template_id = seed.template_id
          AND version.version_number = 1
 );
