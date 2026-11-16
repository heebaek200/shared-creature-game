package com.team.sharedcreature.quest.integration;

import com.team.sharedcreature.quest.application.QuestPeriodAssignmentService;
import com.team.sharedcreature.quest.application.QuestPeriodCalendar;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL에 퀘스트 기간·배정 Snapshot이 원자적으로 기록되는지 검증합니다.
 * 기존 데이터가 없는 test 전용 DB에서만 실행합니다.
 * 서버 스케줄러는 비활성화하고 가변 Clock으로 기간 경계를 제어합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestPeriodAssignmentIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = FIRST_START.plus(Duration.ofDays(28));
    private static final Instant NOW = FIRST_START.plus(Duration.ofDays(1)).plus(Duration.ofHours(3));

    @TestConfiguration
    static class TimeConfig {
        /**
         * 운영 Cron을 쓰지 않고 동일한 시간 참조를 모든 업무 서비스에 제공합니다.
         * 테스트마다 Clock을 초기화하여 결과가 실행 순서에 의존하지 않게 합니다.
         * 실제 시스템 시각은 변경하지 않습니다.
         */
        @Bean
        @Primary
        AdjustableClock questPersistenceTestClock() {
            return new AdjustableClock(NOW);
        }
    }

    static class AdjustableClock extends Clock {
        private final AtomicReference<Instant> value;
        AdjustableClock(Instant now) { value = new AtomicReference<>(now); }
        void set(Instant now) { value.set(now); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return value.get(); }
    }

    @Autowired private AdjustableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeasonRepository seasons;
    @Autowired private QuestPeriodCalendar calendar;
    @Autowired private QuestPeriodAssignmentService assignmentService;

    private Long seasonId;

    /**
     * 최초 시즌만 실제 JPA로 생성해 현재 유효한 ACTIVE 시즌을 준비합니다.
     * 카탈로그 시드는 각 테스트가 의도한 개수만 생성합니다.
     * 실패했다면 테스트 데이터가 남았다고 알리고 다른 데이터를 지우지 않습니다.
     */
    @BeforeEach
    void setUp() {
        clock.set(NOW);
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"), "시즌이 없는 독립 test DB만 사용하세요.");
        Season season = seasons.saveAndFlush(Season.schedule(1, FIRST_START, FIRST_END, NOW));
        assertTrue(season.activateAfterInitialization(NOW));
        seasonId = seasons.saveAndFlush(season).getId();
    }

    /**
     * 이 테스트가 생성한 기간·Snapshot·템플릿·시즌 데이터를 FK 역순으로 정리합니다.
     * 실제 운영 데이터와 다른 test 프로필 DB를 전제로 합니다.
     * 일부 검증이 실패한 경우에도 생성된 테스트 행을 삭제합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId == null) return;
        // 먼저 배정을 삭제한 뒤 기간과 버전·템플릿을 삭제합니다.
        jdbc.update("DELETE FROM quest_assignments WHERE quest_period_id IN "
                + "(SELECT id FROM quest_periods WHERE season_id = ?)", seasonId);
        jdbc.update("DELETE FROM quest_periods WHERE season_id = ?", seasonId);
        jdbc.update("DELETE FROM quest_template_versions WHERE quest_template_id IN "
                + "(SELECT id FROM quest_templates WHERE code LIKE 'F3-%')");
        jdbc.update("DELETE FROM quest_templates WHERE code LIKE 'F3-%'");
        jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 일일 3개와 주간 7개 Snapshot을 각각 한 번만 생성하는지 검증합니다.
     * 같은 기간 재실행에서는 원본 기간 ID와 슬롯 수가 유지돼야 합니다.
     * 최종 배정은 ACTIVE 상태이므로 기존 조회 API에서 사용할 수 있어야 합니다.
     */
    @Test
    void createsDailyAndWeeklyAssignmentsExactlyOnce() {
        seedCatalog("DAILY", 5, 4, 3);
        seedCatalog("WEEKLY", 6, 4, 4);
        var daily = window("DAILY");
        var weekly = window("WEEKLY");

        var firstDaily = assignmentService.assign(daily);
        var firstWeekly = assignmentService.assign(weekly);
        assertEquals(QuestPeriodAssignmentService.Outcome.CREATED, firstDaily.outcome());
        assertEquals(QuestPeriodAssignmentService.Outcome.CREATED, firstWeekly.outcome());
        assertEquals(3L, firstDaily.assignmentCount());
        assertEquals(7L, firstWeekly.assignmentCount());

        // 재실행은 UNIQUE 기간 한 건을 재사용하며 Snapshot을 추가하지 않습니다.
        var secondDaily = assignmentService.assign(daily);
        var secondWeekly = assignmentService.assign(weekly);
        assertEquals(QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, secondDaily.outcome());
        assertEquals(QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, secondWeekly.outcome());
        assertEquals(firstDaily.periodId(), secondDaily.periodId());
        assertEquals(firstWeekly.periodId(), secondWeekly.periodId());
        assertEquals(2L, count("SELECT COUNT(*) FROM quest_periods"));
        assertEquals(10L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals(2L, count("SELECT COUNT(*) FROM quest_periods WHERE status = 'ACTIVE'"));
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_assignments WHERE quest_period_id = ?",
                firstDaily.periodId()));
        assertEquals(7L, count("SELECT COUNT(*) FROM quest_assignments WHERE quest_period_id = ?",
                firstWeekly.periodId()));
    }

    /**
     * 부족한 활성 템플릿은 실패 기간만 남기고 일부 슬롯을 저장하지 않습니다.
     * 이후 카탈로그를 보충하면 기존 FAILED 행을 재활용해 ACTIVE로 변경합니다.
     * 성공 후 실패 사유가 사라지고 기간 ID가 유지돼야 합니다.
     */
    @Test
    void failedPeriodCanRecoverWithoutPartialAssignments() {
        seedCatalog("DAILY", 2, 4, 3);
        var window = window("DAILY");
        var failed = assignmentService.assign(window);
        assertEquals(QuestPeriodAssignmentService.Outcome.FAILED, failed.outcome());
        assertEquals(0L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals("FAILED", text("SELECT status FROM quest_periods WHERE id = ?", failed.periodId()));
        assertEquals("INSUFFICIENT_ACTIVE_TEMPLATES",
                text("SELECT failure_code FROM quest_periods WHERE id = ?", failed.periodId()));

        // 빠진 LOW 3개를 추가해 최소 활성 5개를 충족시킵니다.
        for (int i = 3; i <= 5; i++) seedTemplate("DAILY", "LOW", i);
        var retried = assignmentService.assign(window);
        assertEquals(QuestPeriodAssignmentService.Outcome.CREATED, retried.outcome());
        assertEquals(failed.periodId(), retried.periodId());
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_assignments"));
        assertEquals("ACTIVE", text("SELECT status FROM quest_periods WHERE id = ?", failed.periodId()));
        assertEquals(0L, count("SELECT COUNT(*) FROM quest_periods WHERE failure_code IS NOT NULL"));
    }

    /**
     * 선택된 템플릿의 버전이 누락되면 기간 전체를 FAILED로 기록합니다.
     * 부분 배정이 남지 않으며 관리자가 버전을 보충하면 같은 기간에서 복구됩니다.
     * 실 DB의 MAX(version_number) JOIN을 포함한 선정 경로를 검증합니다.
     */
    @Test
    void missingSelectedVersionRecordsFailureAndCanRecover() {
        seedCatalog("DAILY", 5, 4, 3);
        // 현재 일일 인덱스는 1이므로 LOW 두 번째 템플릿이 선택됩니다.
        Long templateId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM quest_templates WHERE code = 'F3-D-L-2'", Long.class));
        jdbc.update("DELETE FROM quest_template_versions WHERE quest_template_id = ?", templateId);
        var failed = assignmentService.assign(window("DAILY"));
        assertEquals(QuestPeriodAssignmentService.Outcome.FAILED, failed.outcome());
        assertEquals("MISSING_TEMPLATE_VERSION",
                text("SELECT failure_code FROM quest_periods WHERE id = ?", failed.periodId()));
        assertEquals(0L, count("SELECT COUNT(*) FROM quest_assignments"));

        // 결손 버전을 복구한 뒤 새 기간을 만들지 않고 기존 기간 행을 활성화합니다.
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
                ) VALUES (?, 1, '복구한 제목', 'new', 'SELF_CHECK', '{}', 15, 6, UTC_TIMESTAMP(6))
                """, templateId);
        var recovered = assignmentService.assign(window("DAILY"));
        assertEquals(QuestPeriodAssignmentService.Outcome.CREATED, recovered.outcome());
        assertEquals(failed.periodId(), recovered.periodId());
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_assignments"));
    }

    /**
     * 시간이 지나 만료된 일일 기간을 뒤늦게 새로 만들지 않는지 검증합니다.
     * 현재 날짜의 기간에 대해서만 새 배정을 생성해야 합니다.
     * 이는 서버 중단 중 지난 기간에 보상을 소급하지 않는 설계와 일치합니다.
     */
    @Test
    void doesNotBackfillExpiredDay() {
        seedCatalog("DAILY", 5, 4, 3);
        var expiredWindow = window("DAILY");
        clock.set(NOW.plus(Duration.ofDays(1)));
        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY,
                assignmentService.assign(expiredWindow).outcome());
        assertEquals(0L, count("SELECT COUNT(*) FROM quest_periods"));
        assertEquals(QuestPeriodAssignmentService.Outcome.CREATED,
                assignmentService.assign(window("DAILY")).outcome());
        assertEquals(1L, count("SELECT COUNT(*) FROM quest_periods"));
    }

    /**
     * 확정된 퀘스트는 원본 템플릿 버전이 갱신돼도 Snapshot을 변경하지 않습니다.
     * 재실행은 기존 배정을 읽고 SELECT 후 새 INSERT를 수행하지 않아야 합니다.
     * 버전의 제목과 완료 보상은 과거 배정에 전파되지 않습니다.
     */
    @Test
    void preservesSnapshotWhenNewTemplateVersionIsAdded() {
        seedCatalog("DAILY", 5, 4, 3);
        var first = assignmentService.assign(window("DAILY"));
        Long templateId = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT quest_template_id FROM quest_assignments
                 WHERE quest_period_id = ? AND slot_number = 1
                """, Long.class, first.periodId()));
        String originalTitle = text("""
                SELECT title_snapshot FROM quest_assignments
                 WHERE quest_period_id = ? AND slot_number = 1
                """, first.periodId());

        // 템플릿 최신 버전을 추가하더라도 이미 확정된 배정은 변경하지 않습니다.
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
                ) VALUES (?, 2, '변경된 제목', 'new', 'SELF_CHECK', '{}', 999, 999, UTC_TIMESTAMP(6))
                """, templateId);
        assertEquals(QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE,
                assignmentService.assign(window("DAILY")).outcome());
        assertEquals(originalTitle, text("""
                SELECT title_snapshot FROM quest_assignments
                 WHERE quest_period_id = ? AND slot_number = 1
                """, first.periodId()));
        assertEquals(3L, count("SELECT COUNT(*) FROM quest_assignments"));
    }

    /**
     * 현재 시각이 속한 기간을 첫 시즌의 고정된 기준 시각에서 계산합니다.
     * 주간 기간은 월요일 KST 자정부터 시작하고 일일은 매일 자정 시작합니다.
     * 테스트 Clock만 이동시키므로 실제 DB 서버 시간을 바꾸지 않습니다.
     */
    private QuestPeriodCalendar.PeriodWindow window(String type) {
        return calendar.currentWindow(FIRST_START, clock.instant(), type).orElseThrow();
    }

    /**
     * 특정 기간 유형과 난이도에 필요한 활성 템플릿 카탈로그를 생성합니다.
     * 활성 템플릿마다 버전 1을 생성해 실제 JOIN 및 MAX(version_number)를 검증합니다.
     * 모든 테스트 템플릿의 코드는 F3 접두사로 제한합니다.
     */
    private void seedCatalog(String period, int low, int medium, int high) {
        for (int i = 1; i <= low; i++) seedTemplate(period, "LOW", i);
        for (int i = 1; i <= medium; i++) seedTemplate(period, "MEDIUM", i);
        for (int i = 1; i <= high; i++) seedTemplate(period, "HIGH", i);
    }

    /**
     * DB 고유키 조건을 만족하는 템플릿 1개와 최초 버전을 삽입합니다.
     * 저장된 원본 스냅샷의 제목과 보상은 테스트에서 조회할 수 있습니다.
     * 카탈로그의 회전 순서는 난이도별 1부터 시작합니다.
     */
    private void seedTemplate(String period, String difficulty, int order) {
        String code = "F3-" + period.charAt(0) + "-" + difficulty.charAt(0) + "-" + order;
        // 완성된 테스트 카탈로그가 아닌 경우에는 해당 난이도의 일부만 저장합니다.
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
                """, code, period, difficulty, order);
        Long templateId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM quest_templates WHERE code = ?", Long.class, code));
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
                ) VALUES (?, 1, ?, '테스트 설명', 'SELF_CHECK', '{}', 15, 6, UTC_TIMESTAMP(6))
                """, templateId, "title-" + code);
    }

    /**
     * DB 저장 상태를 JPA 영속성 컨텍스트 캐시 없이 검증합니다.
     * 여러 쿼리에서 같은 숫자 타입을 일관되게 읽습니다.
     * 결과가 NULL이면 테스트를 실패시킵니다.
     */
    private long count(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, parameters));
    }

    /**
     * 기간 상태와 Snapshot 문자열을 DB에서 직접 읽습니다.
     * 최신 Commit 결과를 검증하며 현재 스레드의 JPA 캐시를 사용하지 않습니다.
     * 누락 데이터는 예외로 테스트를 실패시킵니다.
     */
    private String text(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, parameters));
    }
}
