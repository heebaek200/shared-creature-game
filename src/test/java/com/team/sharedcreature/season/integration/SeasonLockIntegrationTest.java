package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 시즌 공유 잠금과 종료 배타 잠금의 대기 관계를 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonLockIntegrationTest {

    private static final int SEASON_NUMBER = 9_016;

    @Autowired SeasonRepository seasonRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private Long seasonId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        jdbcTemplate.update("DELETE FROM seasons WHERE season_number = ?", SEASON_NUMBER);
        jdbcTemplate.update(
                """
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                SEASON_NUMBER,
                Timestamp.from(Instant.parse("2026-11-01T15:00:00Z")),
                Timestamp.from(Instant.parse("2026-11-29T15:00:00Z"))
        );
        seasonId = jdbcTemplate.queryForObject(
                "SELECT id FROM seasons WHERE season_number = ?", Long.class, SEASON_NUMBER);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM seasons WHERE season_number = ?", SEASON_NUMBER);
        executor.shutdownNow();
    }

    @Test
    void 시즌_공유_잠금이_유지되는_동안_종료_배타_잠금은_대기한다() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch sharedAcquired = new CountDownLatch(1);
        CountDownLatch releaseShared = new CountDownLatch(1);
        CountDownLatch exclusiveAcquired = new CountDownLatch(1);

        Future<?> shared = executor.submit(() -> transaction.executeWithoutResult(status -> {
            seasonRepository.findForShareByStatus("ACTIVE", PageRequest.of(0, 1));
            sharedAcquired.countDown();
            await(releaseShared);
        }));
        assertThat(sharedAcquired.await(3, TimeUnit.SECONDS)).isTrue();

        Future<?> exclusive = executor.submit(() -> transaction.executeWithoutResult(status -> {
            seasonRepository.findForUpdateById(seasonId).orElseThrow();
            exclusiveAcquired.countDown();
        }));

        try {
            assertThat(exclusiveAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            releaseShared.countDown();
        }
        assertThat(exclusiveAcquired.await(3, TimeUnit.SECONDS)).isTrue();
        shared.get(3, TimeUnit.SECONDS);
        exclusive.get(3, TimeUnit.SECONDS);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating lock test.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock test was interrupted.", exception);
        }
    }
}
