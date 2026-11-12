package com.team.sharedcreature.quest.integration;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.quest.application.QuestAssignmentNormalizationService;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4-F-4 퀘스트 정상화를 실제 MySQL·JPA·Scheduler 실행권과 함께 검증합니다.
 * 실패 이후 복구, 병렬 실행, 서버 중단 후 현재 기간만 생성하는 정책을 확인합니다.
 * test 전용 DB만 사용하며 Mockito로 업무 서비스를 대체하지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestSchedulerRecoveryIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = FIRST_START.plus(Duration.ofDays(28));
    // KST 2026-11-10 03:00: 첫 시즌 일일 인덱스 8, 주간 인덱스 1.
    private static final Instant NOW = FIRST_START.plus(Duration.ofDays(8)).plus(Duration.ofHours(3));

    @TestConfiguration
    static class TimeConfiguration {
        /**
         * Spring의 모든 업무 Service에 동일한 제어 가능한 Clock을 제공합니다.
         * 시스템 시각이 아닌 테스트 시각으로 실행권 Lease와 시즌 상태를 계산합니다.
         * 프로덕션 트리거는 test 프로필에서 비활성화됩니다.
         */
        @Bean
        @Primary
        AdjustableClock questRecoveryClock() {
            return new AdjustableClock(NOW);
        }
    }

    /** 스케줄러 실행 시각을 변경하지만 JVM 기본 시간대는 바꾸지 않습니다. */
    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        AdjustableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            current.set(Objects.requireNonNull(value, "value"));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }

    @Autowired private AdjustableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeasonRepository seasons;
    @Autowired private QuestPeriodRepository questPeriods;
    @Autowired private QuestAssignmentNormalizationService normalizer;

    private Long seasonId;
    private final List<Long> templateIds = new ArrayList<>();

    /**
     * 기동·자정 스케줄러 없이 실제 ACTIVE 시즌 한 건만 준비합니다.
     * 기존 테스트에서 사용한 첫 시즌 달력을 유지해 기간 인덱스를 검증합니다.
     * 전용 DB가 비어 있지 않으면 타인의 데이터를 지우지 않고 실패합니다.
     */
    @BeforeEach
    void setUp() {
        seasonId = null;
        templateIds.clear();
        clock.set(NOW);
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"),
                "반드시 빈 test 전용 DB를 사용하세요. 다른 테스트와 DB를 공유하지 마세요.");

        // 실제 시즌 엔티티를 생성하고 정상화 계층이 사용할 ACTIVE 상태를 Commit합니다.
        Season season = seasons.saveAndFlush(Season.schedule(1, FIRST_START, FIRST_END, NOW));
        assertTrue(season.activateAfterInitialization(NOW));
        seasonId = seasons.saveAndFlush(season).getId();
    }

    /**
     * 이 클래스에서 만든 테이블 행만 외래키 역순으로 정리합니다.
     * 테스트 실패 후에도 다음 테스트에서 빈 시즌 상태로 시작하도록 합니다.
     * 실행권 이력은 시즌 키와 독립이므로 명시적으로 제거합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId == null) return;

        // 배정과 기간을 먼저 삭제하고, 논리 스케줄 실행권을 별도로 정리합니다.
        jdbc.update("DELETE FROM quest_assignments WHERE quest_period_id IN "
                + "(SELECT id FROM quest_periods WHERE season_id = ?)", seasonId);
        jdbc.update("DELETE FROM quest_periods WHERE season_id = ?", seasonId);
        jdbc.update("DELETE FROM scheduler_executions WHERE job_type IN ('QUEST_ASSIGN_DAILY', 'QUEST_ASSIGN_WEEKLY')");
        for (Long templateId : templateIds) {
            jdbc.update("DELETE FROM quest_template_versions WHERE quest_template_id = ?", templateId);
        }
        for (Long templateId : templateIds) {
            jdbc.update("DELETE FROM quest_templates WHERE id = ?", templateId);
        }
        jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 최초 정상화는 일일 3개·주간 7개를 만들고 각 실행을 SUCCEEDED로 확정합니다.
     * 같은 시각 재호출은 기존 Scheduler 실행권을 건너뛰며 DB 데이터가 불변입니다.
     * 지난 일일 8개·주간 1개 기간은 소급 생성하지 않고 JSON 요약에 기록합니다.
     */
    @Test
    void firstRunAndRepeatedRunKeepSingleSnapshotAndExecution() {
        seedCatalog("DAILY", 5, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);

        var first = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(QuestAssignmentNormalizationService.Outcome.CHECKED, first.outcome());
        assertEquals(2, first.periods().size());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, first.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, first.periods().get(1).execution());
        assertEquals(8L, first.periods().get(0).skippedExpiredPeriods());
        assertEquals(1L, first.periods().get(1).skippedExpiredPeriods());

        // 성공 이력과 Snapshot은 각 기간별로 한 세트씩만 존재해야 합니다.
        assertEquals(2L, count("SELECT COUNT(*) FROM quest_periods WHERE status = 'ACTIVE'"));
        assertTrue(questPeriods.findCurrentBySeasonAndType(seasonId, "DAILY", clock.instant()).isPresent());
        assertTrue(questPeriods.findCurrentBySeasonAndType(seasonId, "WEEKLY", clock.instant()).isPresent());
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals(2L, count("SELECT COUNT(*) FROM scheduler_executions WHERE status = 'SUCCEEDED'"));
        assertEquals(8L, skippedInSummary("QUEST_ASSIGN_DAILY"));
        assertEquals(1L, skippedInSummary("QUEST_ASSIGN_WEEKLY"));
        assertEquals(1L, count("SELECT COUNT(*) FROM scheduler_executions "
                + "WHERE job_type = 'QUEST_ASSIGN_DAILY' AND JSON_VALID(result_summary) = 1"));
        List<String> original = snapshots();

        var repeated = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, repeated.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, repeated.periods().get(1).execution());
        assertEquals(original, snapshots());
        assertEquals(2L, count("SELECT COUNT(*) FROM quest_periods"));
        assertEquals(2L, count("SELECT COUNT(*) FROM scheduler_executions"));
        assertEquals(0L, count("SELECT COUNT(*) FROM scheduler_executions WHERE attempt_count <> 1"));
    }

    /**
     * DAILY 최소 템플릿이 부족할 때 FAILED 기간과 FAILED 실행권을 남깁니다.
     * 정상 WEEKLY는 같은 주기에 독립적으로 성공해야 합니다.
     * 템플릿 보완 후 FAILED 실행권을 재선점하며 기존 기간 ID로 ACTIVE가 됩니다.
     */
    @Test
    void insufficientDailyRecoversWithoutChangingPeriodIdOrWeekly() {
        seedCatalog("DAILY", 2, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);
        var failed = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(QuestAssignmentNormalizationService.Outcome.CHECKED, failed.outcome());
        assertNotNull(failed.periods().get(0).error());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, failed.periods().get(1).execution());
        assertEquals("FAILED", periodStatus("DAILY"));
        assertTrue(questPeriods.findCurrentBySeasonAndType(seasonId, "DAILY", clock.instant()).isEmpty());
        assertEquals("ACTIVE", periodStatus("WEEKLY"));
        assertEquals("FAILED", executionStatus("QUEST_ASSIGN_DAILY"));
        assertEquals("SUCCEEDED", executionStatus("QUEST_ASSIGN_WEEKLY"));
        assertEquals(7L, count("SELECT COUNT(*) FROM quest_assignments"));
        Long beforeId = periodId("DAILY");

        // 빠진 LOW 3개를 보충한 다음 같은 논리 기간으로 다시 실행합니다.
        for (int order = 3; order <= 5; order++) {
            seedTemplate("DAILY", "LOW", order);
        }
        var recovered = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, recovered.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, recovered.periods().get(1).execution());
        assertEquals(beforeId, periodId("DAILY"));
        assertEquals("ACTIVE", periodStatus("DAILY"));
        assertTrue(questPeriods.findCurrentBySeasonAndType(seasonId, "DAILY", clock.instant()).isPresent());
        assertEquals("SUCCEEDED", executionStatus("QUEST_ASSIGN_DAILY"));
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals(2L, count("SELECT attempt_count FROM scheduler_executions "
                + "WHERE job_type = 'QUEST_ASSIGN_DAILY'"));
        assertEquals(1L, count("SELECT attempt_count FROM scheduler_executions "
                + "WHERE job_type = 'QUEST_ASSIGN_WEEKLY'"));
    }

    /**
     * 두 스케줄 실행자가 같은 일일·주간 논리 기간에 동시에 접근합니다.
     * 각각 실제 DB 실행권 선점과 배정 서비스를 호출하므로 경쟁 조건을 검증합니다.
     * 두 호출이 모두 끝나도 기간·배정과 성공 실행 이력은 각각 한 건이어야 합니다.
     */
    @Test
    @Timeout(60)
    void simultaneousCallsCreateNoDuplicatePeriodsOrAssignments() throws Exception {
        seedCatalog("DAILY", 5, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 두 스레드가 동일한 시작 신호를 기다리게 해 실제 DB 선점 경합을 유발합니다.
            Future<QuestAssignmentNormalizationService.Result> a = pool.submit(() -> concurrentCall(ready, go));
            Future<QuestAssignmentNormalizationService.Result> b = pool.submit(() -> concurrentCall(ready, go));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "두 스케줄러 작업 스레드가 준비되지 않았습니다.");
            go.countDown();
            var resultA = a.get(35, TimeUnit.SECONDS);
            var resultB = b.get(35, TimeUnit.SECONDS);
            assertEquals(QuestAssignmentNormalizationService.Outcome.CHECKED, resultA.outcome());
            assertEquals(QuestAssignmentNormalizationService.Outcome.CHECKED, resultB.outcome());
        } finally {
            // 한 스레드 실패 시에도 대기 상태를 풀고 Executor가 남지 않도록 종료합니다.
            go.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        // 결과 상태는 DB Commit을 기준으로 검증합니다. 동시 선점 실패는 허용합니다.
        assertEquals(2L, count("SELECT COUNT(*) FROM quest_periods"));
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals(2L, count("SELECT COUNT(*) FROM scheduler_executions WHERE status = 'SUCCEEDED'"));
        assertEquals(0L, count("SELECT COUNT(*) FROM scheduler_executions WHERE status <> 'SUCCEEDED'"));
    }

    /**
     * 서버가 다음 일일 기간 시작 직후까지 꺼져 있었던 상황을 Clock으로 재현합니다.
     * 재시작 시 현재 DAILY만 새로 만들고 이미 ACTIVE인 WEEKLY는 보존합니다.
     * 중단 중 지난 이전 기간은 자동으로 소급 생성하거나 보상을 지급하지 않습니다.
     */
    @Test
    void nextDayRestartCreatesOnlyCurrentDailyAndKeepsWeekly() {
        seedCatalog("DAILY", 5, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);
        var first = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(2, first.periods().size());
        Long weeklyId = periodId("WEEKLY");
        List<String> original = snapshots();

        // KST 2026-11-11 03:00: DAILY 인덱스 9, WEEKLY는 동일한 1주차입니다.
        clock.set(NOW.plus(Duration.ofDays(1)));
        var restarted = normalizer.normalizeForActiveSeason(seasonId);
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, restarted.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, restarted.periods().get(1).execution());
        assertEquals(8L, restarted.periods().get(0).skippedExpiredPeriods());
        assertEquals(weeklyId, periodId("WEEKLY"));
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_periods"));
        assertEquals(13L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals(3L, count("SELECT COUNT(*) FROM scheduler_executions WHERE status = 'SUCCEEDED'"));
        assertTrue(snapshots().containsAll(original), "이전 기간 Snapshot이 변경되면 안 됩니다.");
    }

    /**
     * 병렬 테스트의 시작 신호를 대기한 뒤 실제 Spring 정상화 Service를 호출합니다.
     * 테스트 메서드 자체에는 트랜잭션을 걸지 않습니다.
     * 예외는 Future를 통해 테스트 스레드에 그대로 전달합니다.
     */
    private QuestAssignmentNormalizationService.Result concurrentCall(
            CountDownLatch ready, CountDownLatch go
    ) throws InterruptedException {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("병렬 실행 시작 신호를 받지 못했습니다.");
        }
        return normalizer.normalizeForActiveSeason(seasonId);
    }

    /**
     * 기간 유형별로 필요한 수의 활성 템플릿과 유효한 버전을 DB에 만듭니다.
     * 테스트 전용 F5 접두사를 사용하므로 실제 카탈로그와 섞이지 않습니다.
     * 각 난이도의 회전 순서는 1부터 연속으로 할당합니다.
     */
    private void seedCatalog(String type, int low, int medium, int high) {
        for (int order = 1; order <= low; order++) seedTemplate(type, "LOW", order);
        for (int order = 1; order <= medium; order++) seedTemplate(type, "MEDIUM", order);
        for (int order = 1; order <= high; order++) seedTemplate(type, "HIGH", order);
    }

    /**
     * 실제 템플릿·버전 스키마를 사용하여 하나의 선택 후보를 구성합니다.
     * Snapshot에는 테스트에서 구분 가능한 제목과 보상값이 기록됩니다.
     * INSERT 후 생성된 PK를 보유해 데이터 정리 범위를 제한합니다.
     */
    private void seedTemplate(String type, String difficulty, int order) {
        String code = "F5-" + type.charAt(0) + "-" + difficulty.charAt(0) + "-" + order;
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
                ) VALUES (?, 1, ?, 'integration test', 'SELF_CHECK', '{}', 15, 6, UTC_TIMESTAMP(6))
                """, id, "test-" + code);
    }

    /**
     * 현재 기간이 하나인 테스트에서 타입별 저장 상태를 읽습니다.
     * JdbcTemplate을 사용하여 영속성 컨텍스트의 캐시를 배제합니다.
     * 결과가 없으면 실패해 누락된 생성 과정을 발견합니다.
     */
    private Long periodId(String type) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM quest_periods WHERE period_type = ? ORDER BY id DESC LIMIT 1",
                Long.class, type));
    }

    /**
     * 가장 최신 기간의 상태를 MySQL에서 직접 읽습니다.
     * Snapshot 유무와 관계없이 실제 기간 상태를 확인합니다.
     * 기간이 없다면 실패하므로 원인 추적이 가능합니다.
     */
    private String periodStatus(String type) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM quest_periods WHERE period_type = ? ORDER BY id DESC LIMIT 1",
                String.class, type));
    }

    /**
     * 각 종류의 논리 실행권이 최신으로 기록한 상태를 조회합니다.
     * 날짜 변환이 개입하지 않도록 job_type 단일 조건을 사용합니다.
     * 동일 기간의 성공·실패 여부를 검증할 때 사용합니다.
     */
    private String executionStatus(String jobType) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM scheduler_executions WHERE job_type = ? ORDER BY id DESC LIMIT 1",
                String.class, jobType));
    }

    /**
     * 성공 실행 JSON에 저장된 생략된 과거 기간 수를 반환합니다.
     * MySQL JSON_EXTRACT를 통해 실제 저장된 결과만 판정합니다.
     * 숫자가 없는 잘못된 Summary는 NULL이므로 테스트가 실패합니다.
     */
    private long skippedInSummary(String jobType) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(result_summary, '$.skippedExpiredCount')) AS UNSIGNED)
                  FROM scheduler_executions
                 WHERE job_type = ?
                """, Long.class, jobType));
    }

    /**
     * 기간·슬롯·제목·보상을 조합해 변경 여부를 비교합니다.
     * 템플릿 버전이 변경되더라도 확정된 Snapshot 자체는 바뀌지 않아야 합니다.
     * 결과 순서를 고정해 재실행 전후 값을 단순 비교할 수 있습니다.
     */
    private List<String> snapshots() {
        return jdbc.queryForList("""
                SELECT CONCAT(quest_period_id, ':', slot_number, ':', title_snapshot, ':', contribution_score_snapshot)
                  FROM quest_assignments
                 ORDER BY quest_period_id, slot_number
                """, String.class);
    }

    /**
     * 단일 숫자 조회에 대해서는 NULL을 허용하지 않습니다.
     * 스케줄 실행 이력과 데이터 개수를 실제 MySQL에서 검증합니다.
     * WHERE 조건을 포함한 COUNT 외 일반 정수 반환에도 사용합니다.
     */
    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }
}
