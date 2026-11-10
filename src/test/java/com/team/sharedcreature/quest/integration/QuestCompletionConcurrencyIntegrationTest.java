package com.team.sharedcreature.quest.integration;

import com.team.sharedcreature.quest.api.QuestCompletionResponse;
import com.team.sharedcreature.quest.api.QuestCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 퀘스트 완료와 보상 트랜잭션의 동시 처리를 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class QuestCompletionConcurrencyIntegrationTest {

    @Autowired private QuestCompletionService questCompletionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> membershipIds = new ArrayList<>();
    private ExecutorService executor;

    private Long groupId;
    private Long seasonId;
    private Long groupSeasonId;
    private Long periodId;
    private Long assignmentId;
    private Long groupCreatureId;
    private Long groupScoreId;
    private Long templateId;
    private Long templateVersionId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        createFixture();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        deleteFixture();
    }

    @Test
    void 같은_사용자가_같은_배정을_동시에_완료하면_보상은_한번만_지급된다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<QuestCompletionResponse> first = submitCompletion(userIds.get(0), start);
        Future<QuestCompletionResponse> second = submitCompletion(userIds.get(0), start);

        start.countDown();
        QuestCompletionResponse firstResult = first.get(10, TimeUnit.SECONDS);
        QuestCompletionResponse secondResult = second.get(10, TimeUnit.SECONDS);

        assertThat(firstResult.getCompletionId()).isEqualTo(secondResult.getCompletionId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_completions WHERE quest_assignment_id = ?",
                Integer.class,
                assignmentId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_reward_ledgers ledger "
                        + "JOIN quest_completions completion ON completion.id = ledger.quest_completion_id "
                        + "WHERE completion.quest_assignment_id = ?",
                Integer.class,
                assignmentId
        )).isEqualTo(2);
        assertThat(creatureExperience()).isEqualTo(300L);
        assertThat(groupScore()).isEqualTo(30L);
    }

    @Test
    void 다른_사용자가_같은_그룹에서_동시에_완료하면_두_보상이_모두_반영된다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<QuestCompletionResponse> first = submitCompletion(userIds.get(0), start);
        Future<QuestCompletionResponse> second = submitCompletion(userIds.get(1), start);

        start.countDown();
        QuestCompletionResponse firstResult = first.get(10, TimeUnit.SECONDS);
        QuestCompletionResponse secondResult = second.get(10, TimeUnit.SECONDS);

        assertThat(firstResult.getCompletionId()).isNotEqualTo(secondResult.getCompletionId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_completions WHERE quest_assignment_id = ?",
                Integer.class,
                assignmentId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_reward_ledgers ledger "
                        + "JOIN quest_completions completion ON completion.id = ledger.quest_completion_id "
                        + "WHERE completion.quest_assignment_id = ?",
                Integer.class,
                assignmentId
        )).isEqualTo(4);
        assertThat(creatureExperience()).isEqualTo(600L);
        assertThat(groupScore()).isEqualTo(60L);
        assertThat(memberContribution(userIds.get(0))).isEqualTo(30L);
        assertThat(memberContribution(userIds.get(1))).isEqualTo(30L);
    }

    @Test
    void 같은_사용자가_같은_사진배정을_동시에_완료해도_보상은_한번만_지급된다()
            throws Exception {
        // 기존 fixture의 배정을 PHOTO_EXIF 유형으로 바꿉니다.
        jdbcTemplate.update(
                "UPDATE quest_assignments "
                        + "SET verification_type_snapshot = 'PHOTO_EXIF' "
                        + "WHERE id = ?",
                assignmentId
        );

        CountDownLatch start = new CountDownLatch(1);
        Future<QuestCompletionResponse> first =
                submitPhotoCompletion(userIds.get(0), start);
        Future<QuestCompletionResponse> second =
                submitPhotoCompletion(userIds.get(0), start);

        start.countDown();

        QuestCompletionResponse firstResult =
                first.get(10, TimeUnit.SECONDS);
        QuestCompletionResponse secondResult =
                second.get(10, TimeUnit.SECONDS);

        // 두 요청은 동일한 완료 이력을 반환해야 합니다.
        assertThat(firstResult.getCompletionId())
                .isEqualTo(secondResult.getCompletionId());

        // 완료 1건과 보상 원장 2건만 생성되어야 합니다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_completions "
                        + "WHERE quest_assignment_id = ?",
                Integer.class,
                assignmentId
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_reward_ledgers ledger "
                        + "JOIN quest_completions completion "
                        + "ON completion.id = ledger.quest_completion_id "
                        + "WHERE completion.quest_assignment_id = ?",
                Integer.class,
                assignmentId
        )).isEqualTo(2);

        assertThat(creatureExperience()).isEqualTo(300L);
        assertThat(groupScore()).isEqualTo(30L);
    }

    private Future<QuestCompletionResponse> submitPhotoCompletion(
            Long userId,
            CountDownLatch start
    ) {
        return executor.submit(() -> {
            await(start);
            return questCompletionService.completePhotoExifAssignment(
                    userId,
                    assignmentId
            );
        });
    }

    private Future<QuestCompletionResponse> submitCompletion(Long userId, CountDownLatch start) {
        return executor.submit(() -> {
            await(start);
            return questCompletionService.completeQuestAssignment(userId, assignmentId);
        });
    }

    private void createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instant now = Instant.now();
        Instant seasonStart = now.minusSeconds(60);
        Instant seasonEnd = now.plusSeconds(60 * 60 * 24 * 27);
        LocalDate monday = LocalDate.now(ZoneId.of("Asia/Seoul"))
                .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        Long firstUserId = insertUser();
        Long secondUserId = insertUser();
        userIds.add(firstUserId);
        userIds.add(secondUserId);
        groupId = insertGroup("퀘스트경합" + suffix, firstUserId);
        membershipIds.add(insertMembership(groupId, firstUserId, "PRIMARY"));
        membershipIds.add(insertMembership(groupId, secondUserId, "MEMBER"));
        seasonId = insertSeason(suffix, seasonStart, seasonEnd);
        groupSeasonId = insertGroupSeason(groupId, seasonId, seasonEnd);
        groupCreatureId = insertGroupCreature(groupSeasonId);
        groupScoreId = insertGroupScore(groupSeasonId);
        templateId = insertTemplate(suffix);
        templateVersionId = insertTemplateVersion(templateId);
        periodId = insertPeriod(seasonId, monday, now);
        assignmentId = insertAssignment(periodId, templateId, templateVersionId, now);
    }

    private Long insertUser() {
        return insertAndGetId(
                """
                INSERT INTO users (
                    status, global_role, last_login_at, last_active_at,
                    version, created_at, updated_at
                ) VALUES ('ACTIVE', 'USER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """
        );
    }

    private Long insertGroup(String name, Long primaryUserId) {
        return insertAndGetId(
                """
                INSERT INTO game_groups (
                    name, normalized_name, description, visibility, status,
                    capacity, current_member_count, primary_admin_user_id,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'concurrency fixture', 'PUBLIC', 'ACTIVE', 20, 2, ?,
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                name,
                name,
                primaryUserId
        );
    }

    private Long insertMembership(Long fixtureGroupId, Long userId, String role) {
        return insertAndGetId(
                """
                INSERT INTO group_memberships (
                    group_id, user_id, role, status, joined_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                fixtureGroupId,
                userId,
                role
        );
    }

    private Long insertSeason(String suffix, Instant startAt, Instant endAt) {
        return insertAndGetId(
                """
                INSERT INTO seasons (
                    season_number, start_at, end_at, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                900_000 + Math.abs(suffix.hashCode() % 90_000),
                Timestamp.from(startAt),
                Timestamp.from(endAt)
        );
    }

    private Long insertGroupSeason(Long fixtureGroupId, Long fixtureSeasonId, Instant deadline) {
        return insertAndGetId(
                """
                INSERT INTO group_seasons (
                    group_id, season_id, selection_deadline_at, selected_species_id,
                    selected_by_user_id, selection_source, selected_at, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, NULL, NULL, NULL, NULL, 'ACTIVE', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                fixtureGroupId,
                fixtureSeasonId,
                Timestamp.from(deadline)
        );
    }

    private Long insertGroupCreature(Long fixtureGroupSeasonId) {
        return insertAndGetId(
                """
                INSERT INTO group_creatures (
                    group_season_id, species_id, selection_source, total_experience,
                    growth_stage, growth_stage_reached_at, hatched_at, status,
                    version, created_at, updated_at
                ) VALUES (?, NULL, NULL, 0, 'EGG', NULL, NULL, 'ACTIVE', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                fixtureGroupSeasonId
        );
    }

    private Long insertGroupScore(Long fixtureGroupSeasonId) {
        return insertAndGetId(
                """
                INSERT INTO season_group_scores (
                    group_season_id, score, score_reached_at, version, created_at, updated_at
                ) VALUES (?, 0, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                fixtureGroupSeasonId
        );
    }

    private Long insertTemplate(String suffix) {
        return insertAndGetId(
                """
                INSERT INTO quest_templates (
                    code, period_type, difficulty, status, rotation_order, created_at, updated_at
                ) VALUES (?, 'WEEKLY', 'LOW', 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                "IT-W-" + suffix,
                100_000 + Math.abs(suffix.hashCode() % 900_000)
        );
    }

    private Long insertTemplateVersion(Long templateId) {
        return insertAndGetId(
                """
                INSERT INTO quest_template_versions (
                    quest_template_id, version_number, title, description,
                    verification_type, verification_parameters, creature_experience,
                    contribution_score, created_at
                ) VALUES (?, 1, '동시성 테스트', 'integration fixture', 'SELF_CHECK',
                          JSON_OBJECT(), 300, 30, UTC_TIMESTAMP(6))
                """,
                templateId
        );
    }

    private Long insertPeriod(Long fixtureSeasonId, LocalDate periodDate, Instant now) {
        return insertAndGetId(
                """
                INSERT INTO quest_periods (
                    season_id, period_type, period_date, start_at, end_at, status,
                    failure_code, failure_detail, version, created_at, updated_at
                ) VALUES (?, 'WEEKLY', ?, ?, ?, 'ACTIVE', NULL, NULL, 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                fixtureSeasonId,
                periodDate,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(60 * 60 * 24 * 7))
        );
    }

    private Long insertAssignment(Long periodId, Long templateId, Long versionId, Instant now) {
        return insertAndGetId(
                """
                INSERT INTO quest_assignments (
                    quest_period_id, quest_template_id, quest_template_version_id,
                    slot_number, title_snapshot, description_snapshot, difficulty_snapshot,
                    verification_type_snapshot, verification_parameters_snapshot,
                    creature_experience_snapshot, contribution_score_snapshot, created_at
                ) VALUES (?, ?, ?, 1, '동시성 테스트', 'integration fixture', 'LOW',
                          'SELF_CHECK', JSON_OBJECT(), 300, 30, ?)
                """,
                periodId,
                templateId,
                versionId,
                Timestamp.from(now)
        );
    }

    private Long insertAndGetId(String sql, Object... values) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long creatureExperience() {
        return jdbcTemplate.queryForObject(
                "SELECT total_experience FROM group_creatures WHERE id = ?",
                Long.class,
                groupCreatureId
        );
    }

    private Long groupScore() {
        return jdbcTemplate.queryForObject(
                "SELECT score FROM season_group_scores WHERE id = ?",
                Long.class,
                groupScoreId
        );
    }

    private Long memberContribution(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM season_member_contributions "
                        + "WHERE group_season_id = ? AND user_id = ?",
                Long.class,
                groupSeasonId,
                userId
        );
    }

    private void deleteFixture() {
        if (assignmentId != null) {
            jdbcTemplate.update(
                    "DELETE ledger FROM quest_reward_ledgers ledger "
                            + "JOIN quest_completions completion ON completion.id = ledger.quest_completion_id "
                            + "WHERE completion.quest_assignment_id = ?",
                    assignmentId
            );
            jdbcTemplate.update(
                    "DELETE ledger FROM creature_experience_ledgers ledger "
                            + "JOIN quest_completions completion ON completion.id = ledger.quest_completion_id "
                            + "WHERE completion.quest_assignment_id = ?",
                    assignmentId
            );
            jdbcTemplate.update("DELETE FROM quest_completions WHERE quest_assignment_id = ?", assignmentId);
            jdbcTemplate.update("DELETE FROM quest_assignments WHERE id = ?", assignmentId);
        }
        if (groupSeasonId != null) {
            jdbcTemplate.update("DELETE FROM season_member_contributions WHERE group_season_id = ?", groupSeasonId);
            jdbcTemplate.update("DELETE FROM group_creatures WHERE group_season_id = ?", groupSeasonId);
            jdbcTemplate.update("DELETE FROM season_group_scores WHERE group_season_id = ?", groupSeasonId);
            jdbcTemplate.update("DELETE FROM group_seasons WHERE id = ?", groupSeasonId);
        }
        if (periodId != null) {
            jdbcTemplate.update("DELETE FROM quest_periods WHERE id = ?", periodId);
        }
        if (templateVersionId != null) {
            jdbcTemplate.update("DELETE FROM quest_template_versions WHERE id = ?", templateVersionId);
        }
        if (templateId != null) {
            jdbcTemplate.update("DELETE FROM quest_templates WHERE id = ?", templateId);
        }
        membershipIds.forEach(id -> jdbcTemplate.update("DELETE FROM group_memberships WHERE id = ?", id));
        if (groupId != null) {
            jdbcTemplate.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
        if (seasonId != null) {
            jdbcTemplate.update("DELETE FROM seasons WHERE id = ?", seasonId);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent completions.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent completion test was interrupted.", exception);
        }
    }
}
