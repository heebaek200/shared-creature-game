package com.team.sharedcreature.quest.integration;

import com.team.sharedcreature.quest.api.CurrentQuestResponse;
import com.team.sharedcreature.quest.api.QuestCompletionResponse;
import com.team.sharedcreature.quest.api.QuestCompletionService;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL에서 퀘스트 완료와 보상 트랜잭션의 동시 처리를 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class QuestCompletionConcurrencyIntegrationTest {

    @Autowired private QuestCompletionService questCompletionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PhotoSubmissionRepository photoSubmissionRepository;

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
        configurePhotoAssignment("W-X-01", "WEEKLY", 2);

        Long firstSubmissionId = insertPhotoSubmission(userIds.get(0), 1);
        Long secondSubmissionId = insertPhotoSubmission(userIds.get(0), 2);
        Instant firstCapturedAt = Instant.now().minusSeconds(60 * 60 * 24);
        Instant secondCapturedAt = Instant.now();

        CountDownLatch start = new CountDownLatch(1);
        Future<Optional<QuestCompletionResponse>> first = submitPhotoCompletion(
                userIds.get(0), firstSubmissionId, firstCapturedAt, start);
        Future<Optional<QuestCompletionResponse>> second = submitPhotoCompletion(
                userIds.get(0), secondSubmissionId, secondCapturedAt, start);

        start.countDown();

        Optional<QuestCompletionResponse> firstResult =
                first.get(10, TimeUnit.SECONDS);
        Optional<QuestCompletionResponse> secondResult =
                second.get(10, TimeUnit.SECONDS);

        // 첫 번째 확정은 진행도만, 두 번째 확정은 완료·보상을 반환해야 합니다.
        assertThat(List.of(firstResult, secondResult))
                .filteredOn(Optional::isPresent)
                .hasSize(1);

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

    @Test
    void 주간_사진의_첫번째_VALID는_진행도만_1_2로_반영한다() {
        configurePhotoAssignment("W-X-01", "WEEKLY", 2);
        Long submissionId = insertPhotoSubmission(userIds.get(0), 1);
        Instant capturedAt = Instant.now();

        Optional<QuestCompletionResponse> result =
                questCompletionService.completePhotoExifAssignment(
                        userIds.get(0),
                        assignmentId,
                        submissionId,
                        capturedAt,
                        new BigDecimal("37.5665000"),
                        new BigDecimal("126.9780000")
                );
        List<CurrentQuestResponse> currentQuests =
                questCompletionService.listCurrentQuests(userIds.get(0), "WEEKLY");

        assertThat(result).isEmpty();
        assertThat(photoStatus(submissionId)).isEqualTo("VALID");
        assertThat(completionCount()).isZero();
        assertThat(currentQuests).singleElement().satisfies(quest -> {
            assertThat(quest.getProgress().getCurrent()).isEqualTo(1);
            assertThat(quest.getProgress().getTarget()).isEqualTo(2);
            assertThat(quest.getPhotoAttemptsRemaining()).isEqualTo(4);
        });
    }

    @Test
    void 같은_KST_날짜의_VALID_사진은_여러장이어도_1_2를_유지한다() {
        configurePhotoAssignment("W-X-01", "WEEKLY", 2);
        Long firstSubmissionId = insertPhotoSubmission(userIds.get(0), 1);
        Long secondSubmissionId = insertPhotoSubmission(userIds.get(0), 2);
        Instant sameDayMorning = LocalDate.now(ZoneId.of("Asia/Seoul"))
                .minusDays(1)
                .atTime(10, 0)
                .atZone(ZoneId.of("Asia/Seoul"))
                .toInstant();

        Optional<QuestCompletionResponse> first =
                completePhoto(userIds.get(0), firstSubmissionId, sameDayMorning);
        Optional<QuestCompletionResponse> second =
                completePhoto(userIds.get(0), secondSubmissionId, sameDayMorning.plusSeconds(3600));
        List<CurrentQuestResponse> currentQuests =
                questCompletionService.listCurrentQuests(userIds.get(0), "WEEKLY");

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(completionCount()).isZero();
        assertThat(currentQuests).singleElement().satisfies(quest -> {
            assertThat(quest.getProgress().getCurrent()).isEqualTo(1);
            assertThat(quest.getProgress().getTarget()).isEqualTo(2);
        });
    }

    @Test
    void 일일_사진은_첫번째_VALID에서_기존처럼_완료된다() {
        configurePhotoAssignment("D-X-01", "DAILY", 1);
        Long submissionId = insertPhotoSubmission(userIds.get(0), 1);

        Optional<QuestCompletionResponse> result =
                completePhoto(userIds.get(0), submissionId, Instant.now());

        assertThat(result).isPresent();
        assertThat(photoStatus(submissionId)).isEqualTo("VALID");
        assertThat(completionCount()).isEqualTo(1);
        assertThat(creatureExperience()).isEqualTo(300L);
        assertThat(groupScore()).isEqualTo(30L);
    }

    @Test
    void 현재_멤버십_가입전의_VALID_사진은_주간_진행도에서_제외한다() {
        configurePhotoAssignment("W-X-01", "WEEKLY", 2);
        jdbcTemplate.update(
                "UPDATE group_memberships SET joined_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60 * 60 * 24)),
                membershipIds.get(0)
        );
        Long previousMembershipSubmissionId = insertPhotoSubmission(userIds.get(0), 1);
        jdbcTemplate.update(
                "UPDATE photo_submissions "
                        + "SET captured_at = ?, latitude = 37.5665000, longitude = 126.9780000, "
                        + "verification_status = 'VALID' WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60 * 60 * 36)),
                previousMembershipSubmissionId
        );
        Long currentSubmissionId = insertPhotoSubmission(userIds.get(0), 2);

        Optional<QuestCompletionResponse> result =
                completePhoto(userIds.get(0), currentSubmissionId, Instant.now());
        List<CurrentQuestResponse> currentQuests =
                questCompletionService.listCurrentQuests(userIds.get(0), "WEEKLY");

        assertThat(result).isEmpty();
        assertThat(completionCount()).isZero();
        assertThat(currentQuests).singleElement().satisfies(quest -> {
            assertThat(quest.getProgress().getCurrent()).isEqualTo(1);
            assertThat(quest.getProgress().getTarget()).isEqualTo(2);
        });
    }

    @Test
    void completedPhotoAssignmentRejectsAnotherPendingSubmissionWithoutAnotherReward() {
        configurePhotoAssignment("D-X-01", "DAILY", 1);
        Long firstId = insertPhotoSubmission(userIds.get(0), 1);
        Long secondId = insertPhotoSubmission(userIds.get(0), 2);
        completePhoto(userIds.get(0), firstId, Instant.now());

        assertThatThrownBy(() -> completePhoto(userIds.get(0), secondId, Instant.now()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.QUEST_ALREADY_COMPLETED));
        assertThat(photoStatus(secondId)).isEqualTo("PENDING");
        assertThat(completionCount()).isEqualTo(1);
        assertThat(creatureExperience()).isEqualTo(300L);

        // 사진 제출 서비스가 완료 경합 거절을 확정했을 때 적용하는 상태입니다.
        jdbcTemplate.update("UPDATE photo_submissions SET verification_status = 'INVALID', "
                + "failure_reason = 'QUEST_ALREADY_COMPLETED' WHERE id = ?", secondId);
        assertThat(photoSubmissionRepository.countByUser_IdAndQuestAssignment_Id(userIds.get(0), assignmentId))
                .isEqualTo(1);

        // 일반적인 검증 실패는 계속 제출 횟수에 포함됩니다.
        jdbcTemplate.update("UPDATE photo_submissions SET failure_reason = 'EXIF_GPS_MISSING' WHERE id = ?", secondId);
        assertThat(photoSubmissionRepository.countByUser_IdAndQuestAssignment_Id(userIds.get(0), assignmentId))
                .isEqualTo(2);
    }

    private Future<Optional<QuestCompletionResponse>> submitPhotoCompletion(
            Long userId,
            Long photoSubmissionId,
            Instant capturedAt,
            CountDownLatch start
    ) {
        return executor.submit(() -> {
            await(start);
            return questCompletionService.completePhotoExifAssignment(
                    userId,
                    assignmentId,
                    photoSubmissionId,
                    capturedAt,
                    new BigDecimal("37.5665000"),
                    new BigDecimal("126.9780000")
            );
        });
    }

    private Optional<QuestCompletionResponse> completePhoto(
            Long userId,
            Long photoSubmissionId,
            Instant capturedAt
    ) {
        return questCompletionService.completePhotoExifAssignment(
                userId,
                assignmentId,
                photoSubmissionId,
                capturedAt,
                new BigDecimal("37.5665000"),
                new BigDecimal("126.9780000")
        );
    }

    private void configurePhotoAssignment(String templateCode, String periodType, int target) {
        jdbcTemplate.update(
                "UPDATE quest_templates SET code = ?, period_type = ? WHERE id = ?",
                templateCode,
                periodType,
                templateId
        );
        jdbcTemplate.update(
                "UPDATE quest_periods SET period_type = ? WHERE id = ?",
                periodType,
                periodId
        );
        jdbcTemplate.update(
                "UPDATE quest_assignments "
                        + "SET verification_type_snapshot = 'PHOTO_EXIF', "
                        + "verification_parameters_snapshot = JSON_OBJECT('target', ?) "
                        + "WHERE id = ?",
                target,
                assignmentId
        );
    }

    private Long insertPhotoSubmission(Long userId, int attemptNumber) {
        return insertAndGetId(
                """
                INSERT INTO photo_submissions (
                    user_id
                  , quest_assignment_id
                  , storage_key
                  , attempt_number
                  , captured_at
                  , uploaded_at
                  , latitude
                  , longitude
                  , verification_status
                  , failure_reason
                  , file_deleted_at
                  , cleanup_next_attempt_at
                  , cleanup_claim_token
                  , cleanup_claim_expires_at
                  , created_at
                  , updated_at
                ) VALUES (
                    ?
                  , ?
                  , ?
                  , ?
                  , NULL
                  , UTC_TIMESTAMP(6)
                  , NULL
                  , NULL
                  , 'PENDING'
                  , NULL
                  , NULL
                  , NULL
                  , NULL
                  , NULL
                  , UTC_TIMESTAMP(6)
                  , UTC_TIMESTAMP(6)
                )
                """,
                userId,
                assignmentId,
                "integration/" + UUID.randomUUID() + ".jpg",
                attemptNumber
        );
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
                ) VALUES (?, ?, ?, 'ACTIVE', DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 3 DAY),
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
                Timestamp.from(now.minusSeconds(60 * 60 * 24 * 2)),
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
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
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

    private String photoStatus(Long submissionId) {
        return jdbcTemplate.queryForObject(
                "SELECT verification_status FROM photo_submissions WHERE id = ?",
                String.class,
                submissionId
        );
    }

    private int completionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quest_completions WHERE quest_assignment_id = ?",
                Integer.class,
                assignmentId
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
            jdbcTemplate.update("DELETE FROM photo_submissions WHERE quest_assignment_id = ?", assignmentId);
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
