package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.season.application.SeasonCreationService;
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
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL에서 현재 시즌 생성의 Commit, 멱등성, 동시 실행을 검증합니다.
 * 날짜는 2027년 2월 1일로 고정해 4주 달력 중간 구간 생략을 재현합니다.
 * 테스트 데이터는 별도의 MySQL 테스트 DB에서만 만들고 종료 시 삭제합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonCreationIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant CURRENT_START = Instant.parse("2027-01-24T15:00:00Z");
    private static final Instant CURRENT_END = Instant.parse("2027-02-21T15:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeasonCreationService service;

    private int firstSeasonNumber;

    /**
     * 테스트에 주입될 Clock을 미래의 고정 시각으로 지정합니다.
     * 운영 프로필의 Clock Bean은 변경하지 않습니다.
     * 모든 통합 테스트는 동일한 현재 4주 구간을 사용합니다.
     */
    @TestConfiguration
    static class CreationClockConfiguration {
        /**
         * 별도의 테스트용 UTC Clock을 등록합니다.
         * 현재 4주 구간이 2027년 1월 25일 KST 시작임을 고정합니다.
         * 정상화 서비스의 생성자 주입에서 우선 선택되도록 합니다.
         */
        @Bean
        @Primary
        Clock seasonCreationTestClock() {
            return Clock.fixed(Instant.parse("2027-02-01T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    /**
     * 이미 ENDED인 최초 시즌을 독립적으로 생성합니다.
     * 다른 통합 테스트와 시즌 번호가 충돌하지 않도록 난수를 사용합니다.
     * 시드 시작 시각은 기존 설계의 월요일 자정 KST로 고정합니다.
     */
    @BeforeEach
    void setUp() {
        firstSeasonNumber = ThreadLocalRandom.current()
                .nextInt(300_000_000, 700_000_000);

        // 종료된 첫 시즌은 구간 계산의 영속적인 기준 레코드입니다.
        jdbc.update("""
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'ENDED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, firstSeasonNumber, Timestamp.from(FIRST_START), Timestamp.from(FIRST_END));
    }

    /**
     * 이번 테스트가 만든 두 시즌 번호의 레코드만 삭제합니다.
     * 기본 테스트 DB의 외래키 관계를 가진 다른 시즌은 변경하지 않습니다.
     * 실패로 추가 시즌이 생성됐더라도 테스트 데이터만 정리합니다.
     */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM seasons WHERE season_number IN (?, ?)",
                firstSeasonNumber, firstSeasonNumber + 1);
    }

    /**
     * 중간 두 구간을 소급 생성하지 않고 현재 구간만 INSERT합니다.
     * DB 시즌 번호는 최초 번호 + 1이며 달력상 index=3입니다.
     * 두 번째 실행은 현재 시즌을 찾아 멱등으로 종료되어야 합니다.
     */
    @Test
    void currentWindowIsCreatedOnlyOnce() {
        var first = service.createCurrentScheduledIfMissing();
        var second = service.createCurrentScheduledIfMissing();

        assertEquals(SeasonCreationService.Outcome.CREATED, first.outcome());
        assertEquals(SeasonCreationService.Outcome.ALREADY_EXISTS, second.outcome());
        assertEquals(first.seasonId(), second.seasonId());
        assertEquals(2L, countSeasons());

        // 생성 시각과 KST 4주 달력 경계는 서로 다른 값을 가져야 합니다.
        Integer storedNumber = jdbc.queryForObject(
                "SELECT season_number FROM seasons WHERE id = ?", Integer.class, first.seasonId());
        String storedStatus = jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, first.seasonId());
        Timestamp storedStart = jdbc.queryForObject(
                "SELECT start_at FROM seasons WHERE id = ?", Timestamp.class, first.seasonId());
        Timestamp storedEnd = jdbc.queryForObject(
                "SELECT end_at FROM seasons WHERE id = ?", Timestamp.class, first.seasonId());
        Timestamp storedCreated = jdbc.queryForObject(
                "SELECT created_at FROM seasons WHERE id = ?", Timestamp.class, first.seasonId());

        assertEquals(firstSeasonNumber + 1, storedNumber);
        assertEquals("SCHEDULED", storedStatus);
        assertEquals(Timestamp.from(CURRENT_START), storedStart);
        assertEquals(Timestamp.from(CURRENT_END), storedEnd);
        assertEquals(Timestamp.from(Instant.parse("2027-02-01T03:00:00Z")), storedCreated);
    }

    /**
     * 이전 시즌의 종료가 끝나지 않았다면 새 시즌을 생성하지 않습니다.
     * CLOSING에 남은 Snapshot 복구를 먼저 실행해야 합니다.
     * 이전 시즌이 ENDED가 되면 다음 호출에서 생성할 수 있습니다.
     */
    @Test
    void unfinishedPredecessorDoesNotCreateNextSeason() {
        jdbc.update("UPDATE seasons SET status = 'CLOSING' WHERE season_number = ?",
                firstSeasonNumber);

        var result = service.createCurrentScheduledIfMissing();

        assertEquals(SeasonCreationService.Outcome.WAITING_FOR_CLOSURE, result.outcome());
        assertEquals(1L, countSeasons());
    }

    /**
     * 서로 다른 두 트랜잭션에서 동시에 시즌 생성을 요청합니다.
     * 최초 시즌의 배타 잠금과 UNIQUE 제약으로 한 건만 생성해야 합니다.
     * 다른 요청은 대기 후 동일 시즌을 발견해야 합니다.
     *
     * @throws Exception 동시 실행 결과를 수집하지 못한 경우
     */
    @Test
    void concurrentInstancesCreateOneSeason() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            // 두 작업을 시작 지점에 모아 DB 배타 잠금 경합을 만듭니다.
            Future<SeasonCreationService.CreationResult> a = pool.submit(() -> {
                awaitStart(start);
                return service.createCurrentScheduledIfMissing();
            });
            Future<SeasonCreationService.CreationResult> b = pool.submit(() -> {
                awaitStart(start);
                return service.createCurrentScheduledIfMissing();
            });
            start.countDown();

            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(result ->
                    result.outcome() == SeasonCreationService.Outcome.CREATED).count());
            assertEquals(1L, results.stream().filter(result ->
                    result.outcome() == SeasonCreationService.Outcome.ALREADY_EXISTS).count());
            assertEquals(results.get(0).seasonId(), results.get(1).seasonId());
            assertEquals(2L, countSeasons());
        } finally {
            // 예외가 발생해도 모든 대기자를 깨우고 Executor를 종료합니다.
            start.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 두 스레드가 동일한 시작 신호를 받도록 기다립니다.
     * 실행 경합을 만들되 대기 시간이 무제한 늘어나지 않게 합니다.
     * 중단되면 인터럽트를 복원하고 테스트를 실패시킵니다.
     */
    private void awaitStart(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시즌 생성 동시 테스트의 시작 신호가 없습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 테스트 작업이 중단됐습니다.", exception);
        }
    }

    /**
     * 최초 시드 시즌과 새로 생성된 현재 시즌의 총 개수를 확인합니다.
     * 지금 테스트에서는 다른 시즌을 만들지 않아야 합니다.
     * 별도 JDBC 조회로 실제 Commit 결과를 확인합니다.
     */
    private long countSeasons() {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seasons WHERE season_number IN (?, ?)",
                Long.class, firstSeasonNumber, firstSeasonNumber + 1
        );
        return java.util.Objects.requireNonNull(value);
    }
}
