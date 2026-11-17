-- Android Chrome PHOTO_EXIF 수동 테스트용 로컬 데이터 준비 도구입니다.
-- 운영 DB에서는 실행하지 말고, 로컬 DB를 백업한 뒤 사용하세요.
-- 입력값은 SOURCE 실행 전에 설정합니다.
--   @photo_exif_period_type      : DAILY(기본값) 또는 WEEKLY
--   @photo_exif_allow_replacement: 0(기본값) 또는 1
--   @photo_exif_test_user_id     : 선택 사항인 테스트 사용자 ID

SET @photo_exif_period_type = UPPER(COALESCE(@photo_exif_period_type, 'DAILY'));
SET @photo_exif_allow_replacement = COALESCE(@photo_exif_allow_replacement, 0);

DROP PROCEDURE IF EXISTS prepare_photo_exif_test_assignment;

DELIMITER $$

CREATE PROCEDURE prepare_photo_exif_test_assignment()
main: BEGIN
    DECLARE v_now_utc DATETIME(6) DEFAULT UTC_TIMESTAMP(6);
    DECLARE v_result VARCHAR(20) DEFAULT 'SKIPPED';
    DECLARE v_message VARCHAR(1000) DEFAULT NULL;
    DECLARE v_template_code VARCHAR(30) DEFAULT NULL;
    DECLARE v_expected_target INT DEFAULT NULL;
    DECLARE v_expected_assignment_count INT DEFAULT NULL;
    DECLARE v_expected_low_count INT DEFAULT NULL;
    DECLARE v_expected_medium_count INT DEFAULT NULL;
    DECLARE v_expected_high_count INT DEFAULT NULL;
    DECLARE v_season_count INT DEFAULT 0;
    DECLARE v_season_id BIGINT DEFAULT NULL;
    DECLARE v_period_count INT DEFAULT 0;
    DECLARE v_period_id BIGINT DEFAULT NULL;
    DECLARE v_period_start DATETIME(6) DEFAULT NULL;
    DECLARE v_period_end DATETIME(6) DEFAULT NULL;
    DECLARE v_template_count INT DEFAULT 0;
    DECLARE v_template_id BIGINT DEFAULT NULL;
    DECLARE v_template_version_id BIGINT DEFAULT NULL;
    DECLARE v_assignment_count INT DEFAULT 0;
    DECLARE v_assignment_id BIGINT DEFAULT NULL;
    DECLARE v_candidate_id BIGINT DEFAULT NULL;
    DECLARE v_total_count INT DEFAULT 0;
    DECLARE v_low_count INT DEFAULT 0;
    DECLARE v_medium_count INT DEFAULT 0;
    DECLARE v_high_count INT DEFAULT 0;
    DECLARE v_distinct_slot_count INT DEFAULT 0;
    DECLARE v_min_slot INT DEFAULT NULL;
    DECLARE v_max_slot INT DEFAULT NULL;
    DECLARE v_completion_count INT DEFAULT 0;
    DECLARE v_submission_count INT DEFAULT 0;
    DECLARE v_user_completion_count INT DEFAULT NULL;
    DECLARE v_user_attempt_count INT DEFAULT NULL;
    DECLARE v_user_ready VARCHAR(10) DEFAULT 'UNKNOWN';
    DECLARE v_user_message VARCHAR(500) DEFAULT NULL;
    DECLARE v_legacy_cleanup VARCHAR(30) DEFAULT 'NOT_FOUND';
    DECLARE v_error_message TEXT DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        GET DIAGNOSTICS CONDITION 1 v_error_message = MESSAGE_TEXT;
        ROLLBACK;
        SELECT 'ERROR' AS result
             , @photo_exif_period_type AS period_type
             , NULL AS assignment_id
             , v_template_code AS template_code
             , NULL AS verification_type
             , NULL AS start_at
             , NULL AS end_at
             , NULL AS completion_count
             , NULL AS photo_submission_count
             , @photo_exif_test_user_id AS test_user_id
             , NULL AS user_completion_count
             , NULL AS user_attempt_count
             , 'NO' AS test_ready
             , v_legacy_cleanup AS legacy_template_cleanup
             , CONCAT('오류로 모든 변경을 롤백했습니다: ', v_error_message) AS message;
    END;

    START TRANSACTION;

    -- 과거 도구가 남긴 전용 템플릿은 배정 참조가 없을 때만 비활성화합니다.
    IF EXISTS (
        SELECT 1
          FROM quest_templates AS template
         WHERE template.code = 'D-X-EXIF-TEST'
    ) THEN
        IF NOT EXISTS (
            SELECT 1
              FROM quest_assignments AS assignment
              JOIN quest_templates AS template
                ON template.id = assignment.quest_template_id
             WHERE template.code = 'D-X-EXIF-TEST'
        ) THEN
            UPDATE quest_templates AS template
               SET template.status = 'INACTIVE'
                 , template.updated_at = v_now_utc
             WHERE template.code = 'D-X-EXIF-TEST'
               AND template.status <> 'INACTIVE';
            SET v_legacy_cleanup = IF(ROW_COUNT() = 1, 'INACTIVATED', 'ALREADY_INACTIVE');
        ELSE
            SET v_legacy_cleanup = 'SKIPPED_REFERENCED';
        END IF;
    END IF;

    IF @photo_exif_period_type = 'DAILY' THEN
        SET v_template_code = 'D-X-01';
        SET v_expected_target = 1;
        SET v_expected_assignment_count = 3;
        SET v_expected_low_count = 1;
        SET v_expected_medium_count = 1;
        SET v_expected_high_count = 1;
    ELSEIF @photo_exif_period_type = 'WEEKLY' THEN
        SET v_template_code = 'W-X-01';
        SET v_expected_target = 2;
        SET v_expected_assignment_count = 7;
        SET v_expected_low_count = 3;
        SET v_expected_medium_count = 2;
        SET v_expected_high_count = 2;
    ELSE
        SET v_message = 'period_type은 DAILY 또는 WEEKLY여야 합니다.';
        COMMIT;
        SELECT v_result AS result
             , @photo_exif_period_type AS period_type
             , NULL AS assignment_id
             , NULL AS template_code
             , NULL AS verification_type
             , NULL AS start_at
             , NULL AS end_at
             , 0 AS completion_count
             , 0 AS photo_submission_count
             , @photo_exif_test_user_id AS test_user_id
             , NULL AS user_completion_count
             , NULL AS user_attempt_count
             , 'NO' AS test_ready
             , v_legacy_cleanup AS legacy_template_cleanup
             , v_message AS message;
        LEAVE main;
    END IF;

    SELECT COUNT(*)
         , MAX(season.id)
      INTO v_season_count
         , v_season_id
      FROM seasons AS season
     WHERE season.status = 'ACTIVE'
       AND season.start_at <= v_now_utc
       AND season.end_at > v_now_utc;

    IF v_season_count <> 1 THEN
        SET v_message = CONCAT('현재 유효한 ACTIVE 시즌이 1개여야 합니다. 발견: ', v_season_count);
    ELSE
        SELECT COUNT(*)
             , MAX(period.id)
             , MAX(period.start_at)
             , MAX(period.end_at)
          INTO v_period_count
             , v_period_id
             , v_period_start
             , v_period_end
          FROM quest_periods AS period
         WHERE period.season_id = v_season_id
           AND period.period_type = @photo_exif_period_type
           AND period.status = 'ACTIVE'
           AND period.start_at <= v_now_utc
           AND period.end_at > v_now_utc;

        IF v_period_count <> 1 THEN
            SET v_message = CONCAT(
                '현재 유효한 ACTIVE ', @photo_exif_period_type,
                ' 기간이 1개여야 합니다. 발견: ', v_period_count,
                '. Scheduler를 먼저 정상 실행하세요.'
            );
        END IF;
    END IF;

    IF v_message IS NULL THEN
        SELECT COUNT(*)
             , MAX(template.id)
          INTO v_template_count
             , v_template_id
          FROM quest_templates AS template
         WHERE template.code = v_template_code
           AND template.period_type = @photo_exif_period_type
           AND template.difficulty = 'MEDIUM'
           AND template.status = 'ACTIVE';

        IF v_template_count <> 1 THEN
            SET v_message = CONCAT('활성 공식 템플릿 ', v_template_code, '을 정확히 1개 찾을 수 없습니다.');
        ELSE
            SELECT version.id
              INTO v_template_version_id
              FROM quest_template_versions AS version
             WHERE version.quest_template_id = v_template_id
             ORDER BY version.version_number DESC
             LIMIT 1;

            IF v_template_version_id IS NULL THEN
                SET v_message = CONCAT('공식 템플릿 ', v_template_code, '의 버전이 없습니다.');
            ELSEIF NOT EXISTS (
                SELECT 1
                  FROM quest_template_versions AS version
                 WHERE version.id = v_template_version_id
                   AND version.verification_type = 'PHOTO_EXIF'
                   AND CAST(JSON_UNQUOTE(JSON_EXTRACT(version.verification_parameters, '$.target')) AS UNSIGNED)
                       = v_expected_target
            ) THEN
                SET v_message = CONCAT('공식 템플릿 ', v_template_code, ' 최신 버전의 PHOTO_EXIF 계약이 올바르지 않습니다.');
            END IF;
        END IF;
    END IF;

    IF v_message IS NULL THEN
        SELECT COUNT(*)
             , MAX(assignment.id)
          INTO v_assignment_count
             , v_assignment_id
          FROM quest_assignments AS assignment
         WHERE assignment.quest_period_id = v_period_id
           AND assignment.quest_template_id = v_template_id;

        IF v_assignment_count > 1 THEN
            SET v_message = CONCAT('같은 기간에 ', v_template_code, ' 배정이 둘 이상 존재합니다.');
            SET v_assignment_id = NULL;
        ELSEIF v_assignment_count = 1 THEN
            IF EXISTS (
                SELECT 1
                  FROM quest_assignments AS assignment
                  JOIN quest_template_versions AS version
                    ON version.id = assignment.quest_template_version_id
                 WHERE assignment.id = v_assignment_id
                   AND version.quest_template_id = v_template_id
                   AND assignment.title_snapshot = version.title
                   AND assignment.description_snapshot = version.description
                   AND assignment.difficulty_snapshot = 'MEDIUM'
                   AND assignment.verification_type_snapshot = 'PHOTO_EXIF'
                   AND assignment.verification_parameters_snapshot = version.verification_parameters
                   AND assignment.creature_experience_snapshot = version.creature_experience
                   AND assignment.contribution_score_snapshot = version.contribution_score
                   AND CAST(JSON_UNQUOTE(JSON_EXTRACT(assignment.verification_parameters_snapshot, '$.target')) AS UNSIGNED)
                       = v_expected_target
            ) THEN
                SET v_result = 'REUSED';
                SET v_message = '기존 공식 PHOTO_EXIF 배정을 변경 없이 재사용합니다.';
            ELSE
                SET v_message = '기존 공식 PHOTO_EXIF 배정의 버전 또는 Snapshot이 일치하지 않아 변경하지 않았습니다.';
                SET v_assignment_id = NULL;
            END IF;
        END IF;
    END IF;

    IF v_message IS NULL AND v_assignment_count = 0 THEN
        IF @photo_exif_allow_replacement <> 1 THEN
            SET v_message = '공식 PHOTO_EXIF 배정이 없습니다. 교체하려면 백업 후 @photo_exif_allow_replacement = 1로 다시 실행하세요.';
        ELSE
            SELECT COUNT(*)
                 , SUM(assignment.difficulty_snapshot = 'LOW')
                 , SUM(assignment.difficulty_snapshot = 'MEDIUM')
                 , SUM(assignment.difficulty_snapshot = 'HIGH')
                 , COUNT(DISTINCT assignment.slot_number)
                 , MIN(assignment.slot_number)
                 , MAX(assignment.slot_number)
              INTO v_total_count
                 , v_low_count
                 , v_medium_count
                 , v_high_count
                 , v_distinct_slot_count
                 , v_min_slot
                 , v_max_slot
              FROM quest_assignments AS assignment
             WHERE assignment.quest_period_id = v_period_id;

            IF v_total_count <> v_expected_assignment_count
                    OR v_low_count <> v_expected_low_count
                    OR v_medium_count <> v_expected_medium_count
                    OR v_high_count <> v_expected_high_count
                    OR v_distinct_slot_count <> v_expected_assignment_count
                    OR v_min_slot <> 1
                    OR v_max_slot <> v_expected_assignment_count THEN
                SET v_message = CONCAT(
                    '현재 배정 구성이 Scheduler 규칙과 다릅니다. total/low/medium/high=',
                    v_total_count, '/', v_low_count, '/', v_medium_count, '/', v_high_count,
                    '. 데이터를 변경하지 않았습니다.'
                );
            ELSE
                SELECT assignment.id
                  INTO v_candidate_id
                  FROM quest_assignments AS assignment
                 WHERE assignment.quest_period_id = v_period_id
                   AND assignment.difficulty_snapshot = 'MEDIUM'
                   AND assignment.verification_type_snapshot IN ('SELF_CHECK', 'AGGREGATE', 'SERVER_EVENT')
                   AND NOT EXISTS (
                        SELECT 1
                          FROM quest_completions AS completion
                         WHERE completion.quest_assignment_id = assignment.id
                   )
                   AND NOT EXISTS (
                        SELECT 1
                          FROM photo_submissions AS submission
                         WHERE submission.quest_assignment_id = assignment.id
                   )
                 ORDER BY CASE assignment.verification_type_snapshot
                              WHEN 'SELF_CHECK' THEN 1
                              WHEN 'AGGREGATE' THEN 2
                              WHEN 'SERVER_EVENT' THEN 3
                              ELSE 4
                          END
                        , assignment.slot_number
                        , assignment.id
                 LIMIT 1
                   FOR UPDATE;

                IF v_candidate_id IS NULL THEN
                    SET v_message = '완료·사진 제출 이력이 없는 안전한 MEDIUM 교체 후보가 없습니다.';
                ELSE
                    UPDATE quest_assignments AS assignment
                      JOIN quest_template_versions AS version
                        ON version.id = v_template_version_id
                      LEFT JOIN quest_completions AS completion
                        ON completion.quest_assignment_id = assignment.id
                      LEFT JOIN photo_submissions AS submission
                        ON submission.quest_assignment_id = assignment.id
                      LEFT JOIN quest_assignments AS duplicate_assignment
                        ON duplicate_assignment.quest_period_id = v_period_id
                       AND duplicate_assignment.quest_template_id = v_template_id
                       AND duplicate_assignment.id <> assignment.id
                       SET assignment.quest_template_id = v_template_id
                         , assignment.quest_template_version_id = version.id
                         , assignment.title_snapshot = version.title
                         , assignment.description_snapshot = version.description
                         , assignment.difficulty_snapshot = 'MEDIUM'
                         , assignment.verification_type_snapshot = version.verification_type
                         , assignment.verification_parameters_snapshot = version.verification_parameters
                         , assignment.creature_experience_snapshot = version.creature_experience
                         , assignment.contribution_score_snapshot = version.contribution_score
                     WHERE assignment.id = v_candidate_id
                       AND assignment.quest_period_id = v_period_id
                       AND completion.id IS NULL
                       AND submission.id IS NULL
                       AND duplicate_assignment.id IS NULL;

                    IF ROW_COUNT() = 1 THEN
                        SET v_assignment_id = v_candidate_id;
                        SET v_result = 'REPLACED';
                        SET v_message = '완료·제출 이력이 없는 MEDIUM 배정 하나를 공식 PHOTO_EXIF 배정으로 교체했습니다.';
                    ELSE
                        SET v_message = '교체 직전 조건이 변경되어 데이터를 수정하지 않았습니다.';
                    END IF;
                END IF;
            END IF;
        END IF;
    END IF;

    IF v_assignment_id IS NOT NULL THEN
        SELECT COUNT(*)
          INTO v_completion_count
          FROM quest_completions AS completion
         WHERE completion.quest_assignment_id = v_assignment_id;

        SELECT COUNT(*)
          INTO v_submission_count
          FROM photo_submissions AS submission
         WHERE submission.quest_assignment_id = v_assignment_id;

        IF @photo_exif_test_user_id IS NULL THEN
            SET v_user_ready = 'UNKNOWN';
        ELSE
            SELECT COUNT(*)
              INTO v_user_completion_count
              FROM quest_completions AS completion
             WHERE completion.quest_assignment_id = v_assignment_id
               AND completion.user_id = @photo_exif_test_user_id;

            SELECT COUNT(*)
              INTO v_user_attempt_count
              FROM photo_submissions AS submission
             WHERE submission.quest_assignment_id = v_assignment_id
               AND submission.user_id = @photo_exif_test_user_id;

            IF NOT EXISTS (
                SELECT 1
                  FROM users AS app_user
                 WHERE app_user.id = @photo_exif_test_user_id
                   AND app_user.status = 'ACTIVE'
            ) THEN
                SET v_user_ready = 'NO';
                SET v_user_message = '테스트 사용자가 없거나 ACTIVE 상태가 아닙니다.';
            ELSEIF NOT EXISTS (
                SELECT 1
                  FROM group_memberships AS membership
                  JOIN group_seasons AS group_season
                    ON group_season.group_id = membership.group_id
                   AND group_season.season_id = v_season_id
                   AND group_season.status = 'ACTIVE'
                 WHERE membership.user_id = @photo_exif_test_user_id
                   AND membership.status = 'ACTIVE'
            ) THEN
                SET v_user_ready = 'NO';
                SET v_user_message = '테스트 사용자의 활성 멤버십 또는 현재 ACTIVE 그룹 시즌이 없습니다.';
            ELSEIF v_user_completion_count > 0 THEN
                SET v_user_ready = 'NO';
                SET v_user_message = '테스트 사용자가 이미 이 배정을 완료했습니다.';
            ELSEIF v_user_attempt_count >= 5 THEN
                SET v_user_ready = 'NO';
                SET v_user_message = '테스트 사용자가 사진 제출 5회를 모두 사용했습니다.';
            ELSE
                SET v_user_ready = 'YES';
                SET v_user_message = '테스트 사용자가 사진을 제출할 수 있습니다.';
            END IF;

            SET v_message = CONCAT(v_message, ' ', v_user_message);
        END IF;
    ELSE
        SET v_user_ready = 'NO';
    END IF;

    COMMIT;

    SELECT v_result AS result
         , @photo_exif_period_type AS period_type
         , v_assignment_id AS assignment_id
         , v_template_code AS template_code
         , IF(v_assignment_id IS NULL, NULL, 'PHOTO_EXIF') AS verification_type
         , v_period_start AS start_at
         , v_period_end AS end_at
         , v_completion_count AS completion_count
         , v_submission_count AS photo_submission_count
         , @photo_exif_test_user_id AS test_user_id
         , v_user_completion_count AS user_completion_count
         , v_user_attempt_count AS user_attempt_count
         , v_user_ready AS test_ready
         , v_legacy_cleanup AS legacy_template_cleanup
         , v_message AS message;
END$$

DELIMITER ;

CALL prepare_photo_exif_test_assignment();
DROP PROCEDURE prepare_photo_exif_test_assignment;
