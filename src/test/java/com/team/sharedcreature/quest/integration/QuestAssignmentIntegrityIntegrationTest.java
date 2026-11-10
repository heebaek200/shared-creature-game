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
 * 정상 배정을 완료한 뒤 테스트 데이터만 삭제하거나 상태를 바꿔 감지 여부를 확인합니다.
 * 불일치 복구 과정이 원본 Snapshot을 자동 덮어쓰지 않는 것도 검증합니다.
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

    @TestConfiguration
    static class TimeConfiguration {
        /**
         * 시즌과 Scheduler의 공통 Clock을 고정하여 재현성을 확보합니다.
         * 실제 운영 시스템 시각에 의존하지 않습니다.
         * 테스트 프로필에서는 실제 Cron 실행이 비활성화됩니다.
         */
        @Bean
        @Primary
        FixedQuestClock r3FixedQuestClock() {
            return new FixedQuestClock(NOW);
        }
    }

    /**
     * Clock을 고정하되 일반 Spring Clock 타입으로 주입되도록 합니다.
     * 필요 시 테스트 메서드에서 논리 시각을 변경할 수 있습니다.
     * JVM의 시간대 설정은 변경하지 않습니다.
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
     * 빈 MySQL test DB에서 ACTIVE 시즌과 최소 공식 카탈로그를 생성합니다.
     * 실제 시즌과 Scheduler 저장소를 사용하며 Mock은 사용하지 않습니다.
     * 다른 사람의 데이터를 지우지 않도록 시즌이 남아 있으면 즉시 실패합니다.
     */
    @BeforeEach
    void setUp() {
        seasonId = null;
        templateIds.clear();
        clock.set(NOW);
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"),
                "반드시 비어 있는 별도 test DB만 사용하세요.");

        // 각 서비스의 실제 JPA 트랜잭션에서 읽을 시즌 엔티티를 생성합니다.
        Season season = seasons.saveAndFlush(Season.schedule(1, FIRST_START, FIRST_END, NOW));
        assertTrue(season.activateAfterInitialization(NOW));
        seasonId = seasons.saveAndFlush(season).getId();
        seedCatalog("DAILY", 5, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);
    }

    /**
     * 테스트가 생성한 퀘스트·Scheduler·템플릿·시즌 행만 삭제합니다.
     * 자식 FK를 먼저 지워 무결성 제약을 유지합니다.
     * 테스트 중 기간 행을 수동 삭제했어도 정상 정리가 가능해야 합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId == null) return;
        jdbc.update("DELETE FROM quest_assignments WHERE quest_period_id IN "
                + "(SELECT id FROM quest_periods WHERE season_id = ?)", seasonId);
        jdbc.update("DELETE FROM quest_periods WHERE season_id = ?", seasonId);
        jdbc.update("DELETE FROM scheduler_executions "
                + "WHERE job_type IN ('QUEST_ASSIGN_DAILY', 'QUEST_ASSIGN_WEEKLY')");
        for (Long id : templateIds) {
            jdbc.update("DELETE FROM quest_template_versions WHERE quest_template_id = ?", id);
        }
        for (Long id : templateIds) {
            jdbc.update("DELETE FROM quest_templates WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 데이터가 정상인 경우 재호출은 SKIPPED지만 오류가 없어야 합니다.
     * 정상 배정 10개와 Scheduler 성공 2개가 그대로 남는지 확인합니다.
     * 검사기 도입 후에도 멱등 동작이 유지되어야 합니다.
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
     * Scheduler SUCCEEDED를 남긴 채 현재 DAILY 기간 자체를 제거합니다.
     * 재호출은 SKIPPED라도 PERIOD_MISSING을 오류에 표시해야 합니다.
     * 손상된 과거 배정을 조용히 재생성하면 안 됩니다.
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
     * 정상 완료 뒤 ACTIVE 기간의 슬롯 한 개를 테스트 DB에서만 삭제합니다.
     * 기간 상태가 ACTIVE여도 정확히 3개인지 확인해야 합니다.
     * 자동 수정 없이 진단 정보가 반환되어야 합니다.
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
     * Scheduler SUCCEEDED인데 실제 기간을 FAILED로 바꾸는 상황을 재현합니다.
     * 성공 이력과 업무 상태의 모순을 진단하고 변경하지 않습니다.
     * 다른 WEEKLY 퀘스트는 정상 배정 상태를 유지합니다.
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
     * 조회 가능한 퀘스트 카탈로그를 난이도별로 구성합니다.
     * 실제 JPA Selector의 최소 개수 검사와 순회 규칙을 만족합니다.
     * 본 테스트에서 생성한 버전만 삭제하도록 PK 목록을 기록합니다.
     */
    private void seedCatalog(String type, int low, int medium, int high) {
        for (int i = 1; i <= low; i++) seedTemplate(type, "LOW", i);
        for (int i = 1; i <= medium; i++) seedTemplate(type, "MEDIUM", i);
        for (int i = 1; i <= high; i++) seedTemplate(type, "HIGH", i);
    }

    /**
     * 템플릿과 최초 버전 한 건을 test DB에 INSERT합니다.
     * 코드에는 R3 접두사를 붙여 다른 테스트의 카탈로그와 구별합니다.
     * 검증 형식은 최소 유효 SELF_CHECK Snapshot으로 설정합니다.
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
     * 테스트의 현재 DAILY 논리 기간과 일치하는 DB 행의 ID를 얻습니다.
     * 복수의 기간이 있어도 논리 시작 시각으로 대상을 제한합니다.
     * 누락이 있을 경우 즉시 실패하여 원인 추적을 돕습니다.
     */
    private Long dailyPeriodId() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM quest_periods WHERE period_type = 'DAILY' AND start_at = ?",
                Long.class, java.sql.Timestamp.from(DAILY_START)));
    }

    /**
     * 특정 Scheduler 논리 작업의 실제 성공·실패 상태를 조회합니다.
     * Status는 DB의 저장 값을 그대로 사용합니다.
     * 이력 누락이면 예외가 발생하여 테스트가 실패합니다.
     */
    private String executionStatus(String jobType) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM scheduler_executions WHERE job_type = ?", String.class, jobType));
    }

    /**
     * 실제 MySQL COUNT를 null이 아닌 long 값으로 조회합니다.
     * Snapshot과 실행권 개수 검증에 공통 사용합니다.
     * 영속성 컨텍스트 캐시를 통하지 않습니다.
     */
    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }
}
