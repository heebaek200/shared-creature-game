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
        jdbc.update("""
                UPDATE seasons
                   SET start_at = UTC_TIMESTAMP(6) - INTERVAL 14 DAY
                     , end_at = UTC_TIMESTAMP(6) + INTERVAL 14 DAY
                     , status = 'ACTIVE'
                 WHERE season_number = 1
                """);

        run("db/manual-test-seed.sql");
        assertScenarioCounts();
        assertScenarioConsistency();

        jdbc.update("""
                UPDATE game_groups
                   SET description = '사용자가 변경한 설명'
                 WHERE normalized_name = 'mt93_group_01'
                """);
        run("db/manual-test-seed.sql");

        assertScenarioCounts();
        assertScenarioConsistency();
        assertEquals("사용자가 변경한 설명", jdbc.queryForObject("""
                SELECT description
                  FROM game_groups
                 WHERE normalized_name = 'mt93_group_01'
                """, String.class));
    }

    /** 확정된 시드 소유 행의 건수를 검사합니다. */
    private void assertScenarioCounts() {
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
