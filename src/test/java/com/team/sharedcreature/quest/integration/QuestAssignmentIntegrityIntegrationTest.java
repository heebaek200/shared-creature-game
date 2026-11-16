package com.team.sharedcreature.quest.integration;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.quest.application.QuestAssignmentNormalizationService;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL test 전용 DB에서 SUCCEEDED와 퀘스트 데이터 불일치를 재현합니다.
 * 정상 배정 뒤 테스트 데이터의 상태를 변경하여 감사가 오류를 감지하는지 검사합니다.
 * 정리할 때는 이 클래스의 DAILY/WEEKLY 논리 실행 키로 삭제 범위를 제한합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestAssignmentIntegrityIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = FIRST_START.plus(Duration.ofDays(28));
    private static final Instant NOW = FIRST_START.plus(Duration.ofDays(8)).plus(Duration.ofHours(3));
    private static final Instant DAILY_START = Instant.parse("2026-11-09T15:00:00Z");
    private static final Instant WEEKLY_START = Instant.parse("2026-11-08T15:00:00Z");

    @TestConfiguration
    static class TimeConfiguration {
        /**
         * 시즌 및 Scheduler 서비스의 공통 시간을 고정합니다.
         * 운영 장비의 실제 시간과 관계없이 일일/주간 경계를 재현합니다.
         * test 프로필의 Cron Scheduler는 비활성화된 상태에서 동작합니다.
         */
        @Bean
        @Primary
        FixedQuestClock r3FixedQuestClock() {
            return new FixedQuestClock(NOW);
        }
    }

    /**
     * 공통 고정 시간을 제공하는 테스트 Clock 구현체입니다.
     * 원자적으로 Instant를 교체하여 시점에 따른 테스트를 가능하게 합니다.
     * 전역 시간대를 변경하거나 다른 서버의 현재 시각을 바꾸지 않습니다.
     */
    static final class FixedQuestClock extends Clock {
        private final AtomicReference<Instant> value;

        FixedQuestClock(Instant initial) {
            value = new AtomicReference<>(initial);
        }

        void set(Instant instant) { value.set(Objects.requireNonNull(instant)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return value.get(); }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeasonRepository seasons;
    @Autowired private QuestAssignmentNormalizationService normalizer;
    @Autowired private FixedQuestClock clock;

    private Long seasonId;
    private final List<Long> templateIds = new ArrayList<>();

    /**
     * 비어 있는 MySQL 테스트 DB에 ACTIVE 시즌과 최소 카탈로그를 준비합니다.
     * 기간 생성과 실행권 선점은 실제 JPA 서비스를 사용해 수행합니다.
     * 다른 사용자의 기존 시즌이 존재한다면 임의 삭제하지 않고 실패시킵니다.
     */
    @BeforeEach
    void setUp() {
        seasonId = null;
        templateIds.clear();
        clock.set(NOW);
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"),
                "반드시 비어 있는 별도 test DB만 사용하세요.");

        // 단일 ACTIVE 시즌과 정식 난이도별 카탈로그를 실제 DB에 저장합니다.
        Season season = seasons.saveAndFlush(Season.schedule(1, FIRST_START, FIRST_END, NOW));
        assertTrue(season.activateAfterInitialization(NOW));
        seasonId = seasons.saveAndFlush(season).getId();
        seedCatalog("DAILY", 5, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);
    }

    /**
     * 이 테스트가 저장한 업무 데이터와 실행권만 정리합니다.
     * 이전 구현과 달리 Scheduler 작업 종류만으로 광범위 삭제하지 않습니다.
     * 해당 테스트에 사용한 정확한 두 논리 시작 시각으로 정리 범위를 한정합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId == null) return;
        jdbc.update("DELETE FROM quest_assignments WHERE quest_period_id IN "
                + "(SELECT id FROM quest_periods WHERE season_id = ?)", seasonId);
        jdbc.update("DELETE FROM quest_periods WHERE season_id = ?", seasonId);

        // 동일 작업 유형이라도 다른 scheduled_for의 이력은 건드리지 않습니다.
        jdbc.update("""
                DELETE FROM scheduler_executions
                 WHERE (job_type = 'QUEST_ASSIGN_DAILY' AND scheduled_for = ?)
                    OR (job_type = 'QUEST_ASSIGN_WEEKLY' AND scheduled_for = ?)
                """, Timestamp.from(DAILY_START), Timestamp.from(WEEKLY_START));
        for (Long id : templateIds) {
            jdbc.update("DELETE FROM quest_template_versions WHERE quest_template_id = ?", id);
        }
        for (Long id : templateIds) {
            jdbc.update("DELETE FROM quest_templates WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 성공적으로 완료된 배정 10건의 멱등 재실행을 검증합니다.
     * 동일한 논리 키로 다시 호출할 때 Scheduler는 SKIPPED를 반환합니다.
     * 기간과 완료된 이력은 재실행 후에도 그대로 유지되어야 합니다.
     */
    @Test
    @Timeout(45)
    void succeededAndCompleteSnapshotRemainUnchanged() {
        var first = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, first.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, first.periods().get(1).execution());
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));

        var repeated = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, repeated.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, repeated.periods().get(1).execution());
        assertNull(repeated.periods().get(0).error());
        assertNull(repeated.periods().get(1).error());
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals(2L, count("SELECT COUNT(*) FROM scheduler_executions WHERE status = 'SUCCEEDED'"));
    }

    /**
     * SUCCEEDED 이력을 유지한 채 일일 기간 자체를 고의 삭제합니다.
     * 두 번째 검사는 SKIPPED이면서 PERIOD_MISSING 오류를 보고해야 합니다.
     * 기간이나 완료 데이터를 자동으로 다시 생성하면 안 됩니다.
     */
    @Test
    @Timeout(45)
    void succeededButMissingPeriodIsReportedWithoutRepair() {
        normalizer.normalizeForActiveSeason(seasonId);
        Long dailyId = dailyPeriodId();
        jdbc.update("DELETE FROM quest_assignments WHERE quest_period_id = ?", dailyId);
        jdbc.update("DELETE FROM quest_periods WHERE id = ?", dailyId);

        var checked = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, checked.periods().get(0).execution());
        assertTrue(checked.periods().get(0).error().contains("PERIOD_MISSING"));
        assertNull(checked.periods().get(1).error());
        assertEquals(1L, count("SELECT COUNT(*) FROM quest_periods"));
        assertEquals(7L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals("SUCCEEDED", executionStatus("QUEST_ASSIGN_DAILY"));
    }

    /**
     * ACTIVE 기간의 배정 슬롯 한 건만 삭제해 건수 불일치를 재현합니다.
     * Scheduler 실행 이력은 유지하되 정합성 오류가 반환돼야 합니다.
     * 다른 WEEKLY 기간에는 영향을 주지 않아야 합니다.
     */
    @Test
    @Timeout(45)
    void succeededButMissingSnapshotSlotIsReported() {
        normalizer.normalizeForActiveSeason(seasonId);
        Long dailyId = dailyPeriodId();
        jdbc.update("DELETE FROM quest_assignments WHERE quest_period_id = ? AND slot_number = 1", dailyId);

        var checked = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, checked.periods().get(0).execution());
        assertTrue(checked.periods().get(0).error().contains("ASSIGNMENT_COUNT_MISMATCH"));
        assertNull(checked.periods().get(1).error());
        assertEquals(2L, count("SELECT COUNT(*) FROM quest_assignments WHERE quest_period_id = ?", dailyId));
    }

    /**
     * SUCCEEDED 이력에 연결된 기간을 FAILED로 변경합니다.
     * 기존 실행권과 실제 업무 상태의 불일치를 보고해야 합니다.
     * 원본 Snapshot을 자동 삭제하거나 재생성하지 않습니다.
     */
    @Test
    @Timeout(45)
    void succeededButFailedPeriodIsReported() {
        normalizer.normalizeForActiveSeason(seasonId);
        jdbc.update("UPDATE quest_periods SET status = 'FAILED' WHERE id = ?", dailyPeriodId());

        var checked = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, checked.periods().get(0).execution());
        assertTrue(checked.periods().get(0).error().contains("PERIOD_NOT_ACTIVE"));
        assertNull(checked.periods().get(1).error());
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals("SUCCEEDED", executionStatus("QUEST_ASSIGN_DAILY"));
    }

    /**
     * ACTIVE 기간의 슬롯 개수는 유지하되 MEDIUM 자리에 HIGH를 삽입합니다.
     * SUCCEEDED 실행 이력이 있더라도 난이도 Snapshot의 순서가 틀리면 오류입니다.
     * 데이터는 자동 수정되지 않고 감사 결과에 해당 오류 코드가 남아야 합니다.
     */
    @Test
    @Timeout(45)
    void succeededButWrongDifficultySnapshotIsReported() {
        normalizer.normalizeForActiveSeason(seasonId);
        Long dailyId = dailyPeriodId();
        jdbc.update("""
                UPDATE quest_assignments
                   SET difficulty_snapshot = 'HIGH'
                 WHERE quest_period_id = ?
                   AND slot_number = 2
                """, dailyId);

        // 슬롯 수는 정상이어도 난이도 분포가 잘못됐음을 탐지합니다.
        var checked = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, checked.periods().get(0).execution());
        assertTrue(checked.periods().get(0).error().contains("DIFFICULTY_SLOT_MISMATCH"));
        assertNull(checked.periods().get(1).error());
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_assignments WHERE quest_period_id = ?", dailyId));
    }

    /**
     * 배정 템플릿 A의 버전을 템플릿 B의 유효한 버전 ID로 교체합니다.
     * FK는 모두 존재하므로 DB 제약만으로는 소속 불일치를 확인할 수 없습니다.
     * 감사 서비스가 TEMPLATE_VERSION_MISMATCH를 반환해야 합니다.
     */
    @Test
    @Timeout(45)
    void succeededButVersionBelongsToOtherTemplateIsReported() {
        normalizer.normalizeForActiveSeason(seasonId);
        Long dailyId = dailyPeriodId();
        Long otherVersion = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT quest_template_version_id
                  FROM quest_assignments
                 WHERE quest_period_id = ?
                   AND slot_number = 2
                """, Long.class, dailyId));
        jdbc.update("""
                UPDATE quest_assignments
                   SET quest_template_version_id = ?
                 WHERE quest_period_id = ?
                   AND slot_number = 1
                """, otherVersion, dailyId);

        // 서로 다른 실제 템플릿에 속하는 버전의 연결 관계를 검증합니다.
        var checked = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, checked.periods().get(0).execution());
        assertTrue(checked.periods().get(0).error().contains("TEMPLATE_VERSION_MISMATCH"));
        assertNull(checked.periods().get(1).error());
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_assignments WHERE quest_period_id = ?", dailyId));
    }

    /**
     * 난이도별 정상화 대상 카탈로그를 테스트 DB에 생성합니다.
     * 기존 Selector의 최소 활성 개수와 회전 규칙을 만족시킵니다.
     * 각 템플릿의 PK는 테스트 종료 시 해당 데이터만 삭제할 목적으로 보관합니다.
     */
    private void seedCatalog(String type, int low, int medium, int high) {
        for (int i = 1; i <= low; i++) seedTemplate(type, "LOW", i);
        for (int i = 1; i <= medium; i++) seedTemplate(type, "MEDIUM", i);
        for (int i = 1; i <= high; i++) seedTemplate(type, "HIGH", i);
    }

    /**
     * 테스트의 활성 퀘스트 템플릿과 버전 1을 실제 MySQL에 생성합니다.
     * 코드에는 R3 접두어를 사용해 다른 스크립트의 데이터와 구분합니다.
     * SELF_CHECK 스냅샷 생성에 필요한 모든 필수 필드를 채웁니다.
     */
    private void seedTemplate(String type, String difficulty, int order) {
        String code = "R3-" + type.charAt(0) + "-" + difficulty.charAt(0) + "-" + order;
        jdbc.update("""
                INSERT INTO quest_templates (
                    code
                  , period_type
                  , difficulty
                  , status
                  , rotation_order
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, code, type, difficulty, order);
        Long id = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM quest_templates WHERE code = ?", Long.class, code));
        templateIds.add(id);
        jdbc.update("""
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
                ) VALUES (?, 1, ?, 'R3 test', 'SELF_CHECK', '{}', 100, 10, UTC_TIMESTAMP(6))
                """, id, "test-" + code);
    }

    /**
     * 오늘 생성한 일일 퀘스트 기간의 ID를 조회합니다.
     * 다른 시점의 과거 기간과 혼동하지 않도록 start_at을 사용합니다.
     * 예상 기간이 누락되면 SQL 조회 오류로 테스트가 실패합니다.
     */
    private Long dailyPeriodId() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM quest_periods WHERE period_type = 'DAILY' AND start_at = ?",
                Long.class, Timestamp.from(DAILY_START)));
    }

    /**
     * 논리 작업 종류에 대한 Scheduler 실행 이력을 조회합니다.
     * 테스트에서 생성한 기간만 존재한다는 전용 DB 전제를 유지합니다.
     * 상태 문자열은 실제 DB의 Commit된 값을 사용합니다.
     */
    private String executionStatus(String jobType) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM scheduler_executions WHERE job_type = ?", String.class, jobType));
    }

    /**
     * MySQL COUNT 결과를 원시 long 타입으로 검증합니다.
     * DB null은 비정상 상황이므로 예외로 처리합니다.
     * 업무 서비스와 별도 JDBC 커넥션으로 저장 상태를 조회합니다.
     */
    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }
}
