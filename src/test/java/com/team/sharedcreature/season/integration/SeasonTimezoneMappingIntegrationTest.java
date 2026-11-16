package com.team.sharedcreature.season.integration;

import com.team.sharedcreature._core.time.SeasonCalendar;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 최초 시즌의 UTC DATETIME(6) 시드와 Java Instant 변환을 실제 MySQL에서 검증합니다.
 * 동일한 테스트 전용 DB를 JDBC connectionTimeZone=UTC 및 Asia/Seoul로 각각 실행합니다.
 * 테스트 행은 각 메서드의 트랜잭션 종료 시 Rollback되며 운영 데이터를 건드리지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonTimezoneMappingIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = FIRST_START.plus(Duration.ofDays(28));
    private static final String SQL_START = "2026-11-01 15:00:00.000000";
    private static final String SQL_END = "2026-11-29 15:00:00.000000";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private SeasonCalendar seasonCalendar;

    /**
     * data.sql과 동일한 DATETIME 문자열로 생성한 시즌을 JPA가 올바른 Instant로 읽는지 검사합니다.
     * 연결 URL의 타임존 설정을 바꾸어도 UTC 경계의 의미가 달라져서는 안 됩니다.
     * 테스트에서 추가한 행은 커밋하지 않고 Rollback하여 DB를 깨끗하게 유지합니다.
     */
    @Test
    @Transactional
    void sqlUtcSeedIsReadAsTheExpectedInstant() {
        // 실제 시드와 똑같이 DB에 날짜·시간 문자열을 직접 저장합니다.
        int number = randomSeasonNumber();
        jdbc.update("""
                INSERT INTO seasons (
                      season_number
                    , start_at
                    , end_at
                    , status
                    , version
                    , created_at
                    , updated_at
                ) VALUES (?, '2026-11-01 15:00:00.000000',
                             '2026-11-29 15:00:00.000000',
                             'SCHEDULED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, number);
        Long id = jdbc.queryForObject(
                "SELECT id FROM seasons WHERE season_number = ?", Long.class, number);
        assertNotNull(id);

        // DB의 원본 문자는 바뀌지 않았고, JPA는 정확한 UTC 순간으로 읽어야 합니다.
        assertEquals(SQL_START, formattedColumn(id, "start_at"));
        assertEquals(SQL_END, formattedColumn(id, "end_at"));
        Season season = seasonRepository.findById(id).orElseThrow();
        assertEquals(FIRST_START, season.getStartAt(), diagnostic("JPA startAt"));
        assertEquals(FIRST_END, season.getEndAt(), diagnostic("JPA endAt"));
    }

    /**
     * Java Instant로 저장한 시즌이 DB의 UTC DATETIME(6) 리터럴과 일치하는지 확인합니다.
     * JDBC 문자열 조회는 시간대 변환 없이 실제 저장된 벽시계 값을 관찰하는 데 사용합니다.
     * read 테스트만 통과하고 write 테스트에서 시각이 어긋나는 문제를 검출합니다.
     */
    @Test
    @Transactional
    void jpaInstantIsWrittenAsUtcDatetimeLiteral() {
        // 시즌 팩토리에는 유효한 28일 구간과 구간 내부의 생성 시각을 전달합니다.
        int number = randomSeasonNumber();
        Season created = seasonRepository.saveAndFlush(Season.schedule(
                number, FIRST_START, FIRST_END, FIRST_START.plus(Duration.ofHours(1))));
        assertNotNull(created.getId());

        // JPA가 쓴 DB 컬럼은 문서에 정의된 UTC 날짜·시간 그대로여야 합니다.
        assertEquals(SQL_START, formattedColumn(created.getId(), "start_at"), diagnostic("SQL start_at"));
        assertEquals(SQL_END, formattedColumn(created.getId(), "end_at"), diagnostic("SQL end_at"));
        assertEquals(FIRST_START, created.getStartAt());
        assertEquals(FIRST_END, created.getEndAt());
    }

    /**
     * DB에서 읽은 UTC 시즌 경계가 KST 월요일 자정으로 해석되는지 검증합니다.
     * 달력 계산에는 DB 직접 문자열이 아니라 실제 JPA로 조회한 Instant를 사용합니다.
     * 최초 시즌의 28일 주기와 다음 시즌 경계가 동시에 올바른지 확인합니다.
     */
    @Test
    @Transactional
    void persistedFirstSeasonProducesCorrectKstWindow() {
        // 두 번째 시험 시나리오와 다른 새로운 시드 행을 만듭니다.
        int number = randomSeasonNumber();
        jdbc.update("""
                INSERT INTO seasons (
                      season_number
                    , start_at
                    , end_at
                    , status
                    , version
                    , created_at
                    , updated_at
                ) VALUES (?, '2026-11-01 15:00:00.000000',
                             '2026-11-29 15:00:00.000000',
                             'SCHEDULED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, number);
        Long id = jdbc.queryForObject(
                "SELECT id FROM seasons WHERE season_number = ?", Long.class, number);
        assertNotNull(id);

        // 한국 기준 첫 시즌 월요일 00시와 다음 시즌 월요일 00시를 비교합니다.
        Instant restored = seasonRepository.findById(id).orElseThrow().getStartAt();
        assertEquals(0, restored.atZone(ZoneId.of("Asia/Seoul")).getHour());
        assertEquals(java.time.DayOfWeek.MONDAY,
                restored.atZone(ZoneId.of("Asia/Seoul")).getDayOfWeek());
        SeasonCalendar.SeasonWindow window = seasonCalendar.currentWindow(
                restored, FIRST_END.plus(Duration.ofHours(1))).orElseThrow();
        assertEquals(FIRST_END, window.startAt(), diagnostic("Calendar next start"));
        assertEquals(FIRST_END.plus(Duration.ofDays(28)), window.endAt());
    }

    /**
     * 원본 DATETIME 컬럼을 포맷된 문자열로 읽어 시간대 자동 변환의 영향을 피합니다.
     * 임의의 SQL 컬럼명을 입력받지 않도록 시작·종료 시각 두 컬럼만 허용합니다.
     * JDBC 시간 객체 대신 실제 MySQL DATETIME의 저장 표현을 비교합니다.
     */
    private String formattedColumn(Long seasonId, String column) {
        if (!"start_at".equals(column) && !"end_at".equals(column)) {
            throw new IllegalArgumentException("허용하지 않은 시즌 시간 컬럼입니다.");
        }
        // 쿼리 컬럼명은 위의 두 상수만 허용하며 값은 바인딩합니다.
        return jdbc.queryForObject(
                "SELECT DATE_FORMAT(" + column + ", '%Y-%m-%d %H:%i:%s.%f') "
                        + "FROM seasons WHERE id = ?", String.class, seasonId);
    }

    /**
     * 서로 다른 테스트의 UNIQUE 시즌 번호 충돌 가능성을 낮추는 값을 반환합니다.
     * 원본 실제 시즌 번호와 충분히 떨어진 양의 정수 구간만 사용합니다.
     * 모든 테스트 데이터는 @Transactional에 의해 Rollback됩니다.
     */
    private int randomSeasonNumber() {
        return ThreadLocalRandom.current().nextInt(1_500_000_000, 1_900_000_000);
    }

    /**
     * 테스트 실패 시 JDBC 세션 타임존과 서버 기본값을 오류 메시지에 함께 출력합니다.
     * connectionTimeZone/serverTimezone 옵션은 세션 타임존을 자동 변경하지 않을 수 있습니다.
     * 값은 진단 목적으로만 표시하며 설정을 강제로 변경하지 않습니다.
     */
    private String diagnostic(String context) {
        String session = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        String global = jdbc.queryForObject("SELECT @@global.time_zone", String.class);
        return context + " mismatch; MySQL sessionTimeZone=" + session + ", globalTimeZone=" + global;
    }
}
