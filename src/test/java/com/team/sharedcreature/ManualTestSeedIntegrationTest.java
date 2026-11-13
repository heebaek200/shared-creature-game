package com.team.sharedcreature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #93 로컬 수동 테스트 시드의 건수, 업무 정합성과 재실행 멱등성을 MySQL로 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class ManualTestSeedIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 전체 시드를 두 번 실행해 확정 건수와 기존 상태 보존을 함께 검증합니다.
     */
    @Test
    void seedsCompleteScenarioWithoutDuplicatesOrOverwritingExistingState() {
        run("db/data.sql");
        run("db/quest-catalog.sql");
        jdbc.execute("SET timestamp = UNIX_TIMESTAMP()");
        jdbc.update("""
                UPDATE seasons
                   SET start_at = UTC_TIMESTAMP(6) - INTERVAL 14 DAY
                     , end_at = UTC_TIMESTAMP(6) + INTERVAL 14 DAY
                     , status = 'ACTIVE'
                 WHERE season_number = 1
                """);
        insertUserWithoutProfile();

        run("db/manual-test-seed.sql");
        assertScenarioCounts();
        assertScenarioConsistency();
        assertTimelineConsistency();
        simulateInterruptedAggregateApplication();
        run("db/manual-test-seed.sql");
        assertScenarioCounts();
        assertScenarioConsistency();

        long dailyPeriodCount = count("""
                SELECT COUNT(*)
                  FROM quest_periods
                 WHERE period_type = 'DAILY'
                """);
        long weeklyPeriodCount = count("""
                SELECT COUNT(*)
                  FROM quest_periods
                 WHERE period_type = 'WEEKLY'
                """);

        jdbc.update("""
                UPDATE game_groups
                   SET description = '사용자가 변경한 설명'
                 WHERE normalized_name = 'mt93_group_01'
                """);
        jdbc.update("""
                UPDATE season_group_scores AS score
                  JOIN group_seasons AS group_season ON group_season.id = score.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                   SET score.score = score.score + 777
                     , score.updated_at = UTC_TIMESTAMP(6) + INTERVAL 1 DAY
                 WHERE seed_group.normalized_name = 'mt93_group_01'
                """);
        long changedScore = jdbc.queryForObject("""
                SELECT score.score
                  FROM season_group_scores AS score
                  JOIN group_seasons AS group_season ON group_season.id = score.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                 WHERE seed_group.normalized_name = 'mt93_group_01'
                """, Long.class);
        jdbc.execute("SET timestamp = UNIX_TIMESTAMP() + 8 * 24 * 60 * 60");
        run("db/manual-test-seed.sql");

        assertScenarioCounts();
        assertTimelineConsistency();
        assertEquals("사용자가 변경한 설명", jdbc.queryForObject("""
                SELECT description
                  FROM game_groups
                 WHERE normalized_name = 'mt93_group_01'
                """, String.class));
        assertEquals(changedScore, jdbc.queryForObject("""
                SELECT score.score
                  FROM season_group_scores AS score
                  JOIN group_seasons AS group_season ON group_season.id = score.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                 WHERE seed_group.normalized_name = 'mt93_group_01'
                """, Long.class));
        assertEquals(dailyPeriodCount, count("""
                SELECT COUNT(*)
                  FROM quest_periods
                 WHERE period_type = 'DAILY'
                """));
        assertEquals(weeklyPeriodCount, count("""
                SELECT COUNT(*)
                  FROM quest_periods
                 WHERE period_type = 'WEEKLY'
                """));

        jdbc.update("""
                UPDATE seasons
                   SET status = 'ENDED'
                     , closing_from_status = 'ACTIVE'
                     , updated_at = UTC_TIMESTAMP(6)
                 WHERE season_number = 1
                """);
        run("db/manual-test-seed.sql");
        assertEquals(10L, count("SELECT COUNT(*) FROM season_group_rank_snapshots"));
        assertEquals(52L, count("SELECT COUNT(*) FROM season_member_rank_snapshots"));
    }

    /** 사용자 INSERT만 반영된 중간 상태를 구성해 프로필 복구 경로를 검증합니다. */
    private void insertUserWithoutProfile() {
        jdbc.update("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , withdrawn_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES (
                    'ACTIVE'
                  , 'USER'
                  , UTC_TIMESTAMP(6)
                  , UTC_TIMESTAMP(6)
                  , NULL
                  , 0
                  , '2026-09-03 00:00:01'
                  , UTC_TIMESTAMP(6)
                )
                """);
    }

    /** 완료와 원장 뒤 집계 반영만 중단된 상태를 구성합니다. */
    private void simulateInterruptedAggregateApplication() {
        jdbc.update("""
                UPDATE season_group_scores AS score
                  JOIN group_seasons AS group_season ON group_season.id = score.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                   SET score.score = 0
                     , score.score_reached_at = NULL
                     , score.version = 0
                 WHERE seed_group.normalized_name = 'mt93_group_02'
                """);
        jdbc.update("""
                UPDATE group_creatures AS creature
                  JOIN group_seasons AS group_season ON group_season.id = creature.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                   SET creature.total_experience = 0
                     , creature.version = 0
                 WHERE seed_group.normalized_name = 'mt93_group_02'
                """);
        jdbc.update("""
                UPDATE season_member_contributions AS contribution
                  JOIN group_seasons AS group_season ON group_season.id = contribution.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                   SET contribution.contribution_score = 0
                     , contribution.version = 0
                 WHERE seed_group.normalized_name = 'mt93_group_02'
                """);
    }

    /** 확정된 시드 소유 행의 건수를 검사합니다. */
    private void assertScenarioCounts() {
        assertEquals(60L, count("""
                SELECT COUNT(*)
                  FROM users
                 WHERE created_at BETWEEN '2026-09-03 00:00:01' AND '2026-09-03 00:01:00'
                """));
        assertEquals(60L, count("""
                SELECT COUNT(*)
                  FROM user_profiles
                 WHERE normalized_nickname LIKE 'mt93_user_%'
                """));
        assertEquals(12L, count("""
                SELECT COUNT(*)
                  FROM game_groups
                 WHERE normalized_name LIKE 'mt93_group_%'
                """));
        assertEquals(52L, count("""
                SELECT COUNT(*)
                  FROM group_memberships AS membership
                  JOIN user_profiles AS profile ON profile.user_id = membership.user_id
                 WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                   AND membership.status = 'ACTIVE'
                """));
        assertEquals(120L, count("""
                SELECT COUNT(*)
                  FROM group_memberships AS membership
                  JOIN user_profiles AS profile ON profile.user_id = membership.user_id
                 WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                """));
        assertEquals(10L, count("""
                SELECT COUNT(*)
                  FROM group_seasons AS group_season
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                 WHERE seed_group.normalized_name LIKE 'mt93_group_%'
                """));
        assertEquals(500L, count("""
                SELECT COUNT(*)
                  FROM quest_completions AS completion
                  JOIN user_profiles AS profile ON profile.user_id = completion.user_id
                 WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                """));
        assertEquals(364L, count("""
                SELECT COUNT(*)
                  FROM service_check_ins AS check_in
                  JOIN user_activity_events AS event ON event.id = check_in.activity_event_id
                 WHERE event.deduplication_key LIKE 'MT93:CHECKIN:%'
                """));
        assertEquals(1000L, count("""
                SELECT COUNT(*)
                  FROM quest_reward_ledgers AS ledger
                  JOIN quest_completions AS completion ON completion.id = ledger.quest_completion_id
                  JOIN user_profiles AS profile ON profile.user_id = completion.user_id
                 WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                """));
        assertEquals(500L, count("""
                SELECT COUNT(*)
                  FROM user_activity_events
                 WHERE deduplication_key LIKE 'MT93:QUEST:%'
                """));
        assertTrue(count("""
                SELECT COUNT(DISTINCT business_date)
                  FROM user_activity_events
                 WHERE deduplication_key LIKE 'MT93:QUEST:%'
                """) >= 3L);
        assertEquals(120L, count("""
                SELECT COUNT(*)
                  FROM purchases
                 WHERE order_number LIKE '93000000-0000-0000-0000-%'
                """));
        assertEquals(40L, count("""
                SELECT COUNT(*)
                  FROM refund_requests
                 WHERE request_reason LIKE 'MT93 환불 요청 %'
                """));
        assertEquals(30L, count("""
                SELECT COUNT(*)
                  FROM user_reports
                 WHERE content LIKE 'MT93 신고 시나리오 %'
                """));
        assertEquals(5L, count("""
                SELECT COUNT(*)
                  FROM moderation_actions
                 WHERE reason LIKE 'MT93 신고 강퇴 %'
                """));
        assertEquals(5L, count("""
                SELECT COUNT(*)
                  FROM moderation_actions
                 WHERE reason LIKE 'MT93 비활동 강퇴 %'
                """));
        assertEquals(10L, count("""
                SELECT COUNT(*)
                  FROM admin_audit_logs
                 WHERE reason LIKE 'MT93 신고 감사 %'
                """));
    }

    /** 그룹·보상·결제의 핵심 불변식과 외부 복구 대상 부재를 검사합니다. */
    private void assertScenarioConsistency() {
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM game_groups AS seed_group
                 WHERE seed_group.normalized_name LIKE 'mt93_group_%'
                   AND seed_group.current_member_count <> (
                       SELECT COUNT(*)
                         FROM group_memberships AS membership
                        WHERE membership.group_id = seed_group.id
                          AND membership.status = 'ACTIVE'
                   )
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM social_accounts AS account
                  JOIN user_profiles AS profile ON profile.user_id = account.user_id
                 WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM payment_transactions AS payment
                  JOIN purchases AS purchase ON purchase.id = payment.purchase_id
                 WHERE purchase.order_number LIKE '93000000-0000-0000-0000-%'
                   AND payment.status IN ('REQUESTED', 'UNKNOWN')
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM refund_requests
                 WHERE request_reason LIKE 'MT93 환불 요청 %'
                   AND status IN ('CANCEL_PENDING', 'CANCEL_UNKNOWN')
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM season_group_scores AS score
                  JOIN (
                       SELECT completion.group_season_id
                            , SUM(assignment.contribution_score_snapshot) AS expected_score
                         FROM quest_completions AS completion
                         JOIN quest_assignments AS assignment
                           ON assignment.id = completion.quest_assignment_id
                         JOIN user_profiles AS profile
                           ON profile.user_id = completion.user_id
                        WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                        GROUP BY completion.group_season_id
                  ) AS expected ON expected.group_season_id = score.group_season_id
                 WHERE score.score <> expected.expected_score
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM season_member_contributions AS contribution
                  JOIN (
                       SELECT completion.group_season_id
                            , completion.user_id
                            , SUM(assignment.contribution_score_snapshot) AS expected_score
                         FROM quest_completions AS completion
                         JOIN quest_assignments AS assignment
                           ON assignment.id = completion.quest_assignment_id
                         JOIN user_profiles AS profile
                           ON profile.user_id = completion.user_id
                        WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                        GROUP BY completion.group_season_id, completion.user_id
                  ) AS expected
                    ON expected.group_season_id = contribution.group_season_id
                   AND expected.user_id = contribution.user_id
                 WHERE contribution.contribution_score <> expected.expected_score
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM group_creatures AS creature
                  JOIN (
                       SELECT completion.group_season_id
                            , SUM(assignment.creature_experience_snapshot) AS expected_experience
                         FROM quest_completions AS completion
                         JOIN quest_assignments AS assignment
                           ON assignment.id = completion.quest_assignment_id
                         JOIN user_profiles AS profile
                           ON profile.user_id = completion.user_id
                        WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                        GROUP BY completion.group_season_id
                  ) AS expected ON expected.group_season_id = creature.group_season_id
                 WHERE creature.total_experience <> expected.expected_experience
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM quest_completions AS completion
                  JOIN quest_assignments AS assignment ON assignment.id = completion.quest_assignment_id
                  JOIN quest_periods AS period ON period.id = assignment.quest_period_id
                 WHERE assignment.verification_type_snapshot = 'SERVER_EVENT'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM service_check_ins AS check_in
                        WHERE check_in.user_id = completion.user_id
                          AND check_in.check_in_date = period.period_date
                          AND check_in.activity_event_id IN (
                              SELECT event.id
                                FROM user_activity_events AS event
                               WHERE event.membership_id = completion.membership_id
                                 AND event.group_season_id = completion.group_season_id
                          )
                   )
                """));
        assertEquals(1L, count("""
                SELECT COUNT(*)
                  FROM group_creatures AS creature
                  JOIN group_seasons AS group_season ON group_season.id = creature.group_season_id
                 WHERE creature.growth_stage = 'EGG'
                   AND group_season.status = 'PREPARING'
                   AND group_season.selected_species_id IS NULL
                """));
        assertTrue(count("""
                SELECT COUNT(*)
                  FROM group_creatures
                 WHERE growth_stage = 'GROWING'
                """) >= 1L, () -> jdbc.queryForList("""
                SELECT seed_group.normalized_name, creature.total_experience, creature.growth_stage
                  FROM group_creatures AS creature
                  JOIN group_seasons AS group_season ON group_season.id = creature.group_season_id
                  JOIN game_groups AS seed_group ON seed_group.id = group_season.group_id
                 ORDER BY seed_group.normalized_name
                """).toString());
    }

    /** 그룹 생성부터 신고·조치까지의 시간 순서와 종료 이력의 유일성을 검사합니다. */
    private void assertTimelineConsistency() {
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM group_memberships AS membership
                  JOIN game_groups AS seed_group ON seed_group.id = membership.group_id
                  JOIN user_profiles AS profile ON profile.user_id = membership.user_id
                 WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                   AND (
                       membership.joined_at < seed_group.created_at
                        OR (membership.ended_at IS NOT NULL AND membership.ended_at < membership.joined_at)
                   )
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM (
                       SELECT membership.group_id
                            , membership.user_id
                            , membership.joined_at
                            , COUNT(*) AS duplicate_count
                         FROM group_memberships AS membership
                         JOIN user_profiles AS profile ON profile.user_id = membership.user_id
                        WHERE profile.normalized_nickname LIKE 'mt93_user_%'
                        GROUP BY membership.group_id, membership.user_id, membership.joined_at
                       HAVING COUNT(*) > 1
                       ) AS duplicates
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM user_reports AS report
                 WHERE report.content LIKE 'MT93 신고 시나리오 %'
                   AND (
                       NOT EXISTS (
                           SELECT 1
                             FROM group_memberships AS reporter_membership
                            WHERE reporter_membership.group_id = report.group_id
                              AND reporter_membership.user_id = report.reporter_user_id
                              AND reporter_membership.joined_at <= report.created_at
                              AND (
                                  reporter_membership.ended_at IS NULL
                                   OR reporter_membership.ended_at >= report.created_at
                              )
                       )
                        OR NOT EXISTS (
                           SELECT 1
                             FROM group_memberships AS target_membership
                            WHERE target_membership.group_id = report.group_id
                              AND target_membership.user_id = report.reported_user_id
                              AND target_membership.joined_at <= report.created_at
                              AND (
                                  target_membership.ended_at IS NULL
                                   OR target_membership.ended_at >= report.created_at
                              )
                       )
                   )
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*)
                  FROM moderation_actions AS action
                  JOIN user_reports AS report ON report.id = action.report_id
                 WHERE action.executed_at < report.created_at
                """));
    }

    /** 애플리케이션과 동일한 Classpath SQL 리소스를 현재 테스트 트랜잭션에서 실행합니다. */
    private void run(String path) {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource(path));
        populator.execute(dataSource);
    }

    /** 단일 집계 결과를 primitive long으로 반환합니다. */
    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
