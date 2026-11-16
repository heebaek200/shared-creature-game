package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.season.application.SeasonTransitionService;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시즌 CLOSING 진입의 실제 MySQL 트랜잭션 동작을 검증합니다.
 * REQUIRES_NEW Commit, 중복 종료, 공유·배타 잠금 경합을 테스트합니다.
 * 테스트에서 생성한 시즌 행만 삭제하고 다른 시즌에는 접근하지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonTransitionIntegrationTest {

    @Autowired
    private SeasonTransitionService transitionService;

    @Autowired
    private SeasonRepository seasonRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    private Long seasonId;

    /**
     * 테스트에서 생성한 시즌만 정리합니다.
     * 서비스가 독립 트랜잭션으로 Commit한 데이터도 삭제합니다.
     * 테스트 대상이 아닌 시즌 행에는 영향을 주지 않습니다.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (seasonId != null) {
            jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
        }
    }

    /**
     * 만료된 ACTIVE 시즌의 CLOSING 전환이 Commit되는지 검증합니다.
     * 테스트 메서드 자체는 트랜잭션으로 감싸지 않습니다.
     * Service 반환 후 별도 JDBC 조회로 실제 저장 결과를 확인합니다.
     */
    @Test
    void expiredActiveSeasonIsCommitted() {
        insertSeason("ACTIVE", Instant.now(clock).minusSeconds(60));

        // 독립 트랜잭션으로 상태를 변경하고 Commit합니다.
        assertTrue(transitionService.beginClosingIfExpired(seasonId));

        assertEquals("CLOSING", storedStatus());
        assertEquals(1L, storedVersion());
    }

    /**
     * 만료된 SCHEDULED 시즌이 직접 CLOSING으로 전환되는지 검증합니다.
     * 만료된 초기화 대상을 ACTIVE로 소급 활성화하지 않습니다.
     * 상태 변경은 별도의 DB 조회에서 관찰되어야 합니다.
     */
    @Test
    void expiredScheduledSeasonIsCommitted() {
        insertSeason("SCHEDULED", Instant.now(clock).minusSeconds(60));

        // 중간에 ACTIVE 상태를 거치지 않고 종료합니다.
        assertTrue(transitionService.beginClosingIfExpired(seasonId));

        assertEquals("CLOSING", storedStatus());
        assertEquals(1L, storedVersion());
    }

    /**
     * 종료 시각 이전에는 시즌을 변경하지 않는지 검증합니다.
     * 불필요한 UPDATE로 version과 updated_at이 바뀌어서는 안 됩니다.
     * 종료 대상 발견은 DB 상태와 실제 시각을 함께 사용합니다.
     */
    @Test
    void nonExpiredSeasonDoesNotUpdateDatabase() {
        insertSeason("ACTIVE", Instant.now(clock).plusSeconds(3600));
        Timestamp before = storedUpdatedAt();

        // 종료 시각 전에는 기존 상태를 보존합니다.
        assertFalse(transitionService.beginClosingIfExpired(seasonId));

        assertEquals("ACTIVE", storedStatus());
        assertEquals(0L, storedVersion());
        assertEquals(before, storedUpdatedAt());
    }

    /**
     * 이미 CLOSING인 시즌의 중복 처리가 멱등적인지 검증합니다.
     * 최초 호출만 상태를 변경하고 이후 호출은 false를 반환합니다.
     * 추가 호출이 version 또는 updated_at을 갱신해서는 안 됩니다.
     */
    @Test
    void repeatedClosingDoesNotUpdateAgain() {
        insertSeason("ACTIVE", Instant.now(clock).minusSeconds(60));

        assertTrue(transitionService.beginClosingIfExpired(seasonId));
        Timestamp firstUpdate = storedUpdatedAt();
        long firstVersion = storedVersion();

        // 다시 실행해도 종료 시작 시각과 version이 유지됩니다.
        assertFalse(transitionService.beginClosingIfExpired(seasonId));

        assertEquals("CLOSING", storedStatus());
        assertEquals(firstVersion, storedVersion());
        assertEquals(firstUpdate, storedUpdatedAt());
    }

    /**
     * 외부 트랜잭션이 Rollback돼도 REQUIRES_NEW 결과가 남는지 검증합니다.
     * 별도 트랜잭션을 사용하는 이유를 실제 DB에서 확인합니다.
     * 이 테스트는 내부 서비스 자체의 Rollback을 검증하는 것이 아닙니다.
     */
    @Test
    void newTransactionCommitSurvivesOuterRollback() {
        insertSeason("ACTIVE", Instant.now(clock).minusSeconds(60));
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        // 바깥 트랜잭션은 Rollback하지만 내부의 REQUIRES_NEW는 Commit됩니다.
        assertThrows(IllegalStateException.class, () ->
                outer.executeWithoutResult(status -> {
                    assertTrue(transitionService.beginClosingIfExpired(seasonId));
                    throw new IllegalStateException("rollback the outer transaction");
                })
        );

        assertEquals("CLOSING", storedStatus());
        assertEquals(1L, storedVersion());
    }

    /**
     * 시즌 상태 변경이 같은 트랜잭션에서 Rollback되면 저장되지 않습니다.
     * 이 테스트는 Repository와 엔티티의 트랜잭션 Rollback을 검증합니다.
     * Service의 REQUIRES_NEW 실패 경로를 검증하는 것은 아닙니다.
     */
    @Test
    void rolledBackEntityChangeIsNotPersisted() {
        insertSeason("ACTIVE", Instant.now(clock).minusSeconds(60));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // 명시적으로 Rollback을 요청해 Dirty Checking 결과를 취소합니다.
        transaction.executeWithoutResult(status -> {
            Season season = seasonRepository.findForUpdateById(seasonId).orElseThrow();
            assertTrue(season.beginClosingIfExpired(Instant.now(clock)));
            status.setRollbackOnly();
        });

        assertEquals("ACTIVE", storedStatus());
        assertEquals(0L, storedVersion());
    }

    /**
     * 시즌 공유 잠금을 가진 트랜잭션이 끝나기 전 종료 전환을 막습니다.
     * 배타 잠금은 공유 잠금의 Commit 이후에야 획득할 수 있습니다.
     * 실제 DB 락 대기와 최종 CLOSING Commit을 확인합니다.
     *
     * @throws Exception 동시 실행 결과 수집 중 발생하는 예외
     */
    @Test
    void sharedLockBlocksClosingUntilReleased() throws Exception {
        insertSeason("ACTIVE", Instant.now(clock).minusSeconds(60));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch sharedLocked = new CountDownLatch(1);
        CountDownLatch releaseShared = new CountDownLatch(1);
        CountDownLatch closeInvoked = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try {
            // 같은 시즌 행을 공유 잠금으로 잡고 의도적으로 유지합니다.
            Future<?> reader = executor.submit(() -> transaction.executeWithoutResult(status -> {
                entityManager.find(Season.class, seasonId, LockModeType.PESSIMISTIC_READ);
                sharedLocked.countDown();
                await(releaseShared);
            }));

            Future<Boolean> closer;
            try {
                assertTrue(sharedLocked.await(5, TimeUnit.SECONDS));

                // 별도 스레드에서 서비스의 배타 잠금 요청을 실행합니다.
                closer = executor.submit(() -> {
                    closeInvoked.countDown();
                    try {
                        return transitionService.beginClosingIfExpired(seasonId);
                    } finally {
                        closeFinished.countDown();
                    }
                });
                assertTrue(closeInvoked.await(5, TimeUnit.SECONDS));

                // 공유 잠금이 유지되는 동안 종료 트랜잭션은 끝날 수 없습니다.
                assertFalse(closeFinished.await(300, TimeUnit.MILLISECONDS));
            } finally {
                // 실패 시에도 잠금을 해제해 다른 테스트에 영향을 주지 않습니다.
                releaseShared.countDown();
            }

            reader.get(5, TimeUnit.SECONDS);
            assertTrue(closer.get(5, TimeUnit.SECONDS));
            assertEquals("CLOSING", storedStatus());
        } finally {
            releaseShared.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * DB에 테스트 전용 시즌을 INSERT하고 생성된 ID를 보관합니다.
     * 종료 시각으로부터 정확히 28일 전을 시즌 시작으로 설정합니다.
     * 각 테스트는 고유 시즌 번호와 실제 시각을 사용합니다.
     *
     * @param status 저장할 시즌 상태
     * @param endAt 종료 시각
     */
    private void insertSeason(String status, Instant endAt) {
        int seasonNumber = ThreadLocalRandom.current()
                .nextInt(1_000_000_000, 2_000_000_000);
        Instant startAt = endAt.minus(Duration.ofDays(28));
        GeneratedKeyHolder holder = new GeneratedKeyHolder();

        // JPA 상태 변경 전에 JDBC로 독립적인 테스트 시즌을 준비합니다.
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO seasons (
                        season_number
                      , start_at
                      , end_at
                      , status
                      , version
                      , created_at
                      , updated_at
                    ) VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, seasonNumber);
            statement.setTimestamp(2, Timestamp.from(startAt));
            statement.setTimestamp(3, Timestamp.from(endAt));
            statement.setString(4, status);
            return statement;
        }, holder);

        seasonId = Objects.requireNonNull(holder.getKey()).longValue();
    }

    /**
     * 테스트 시즌의 저장된 상태를 JDBC로 직접 조회합니다.
     * 이전 JPA 영속성 컨텍스트의 캐시에 의존하지 않습니다.
     * 별도 트랜잭션 Commit 여부를 확인하는 데 사용합니다.
     */
    private String storedStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, seasonId
        );
    }

    /**
     * 테스트 시즌의 낙관적 잠금 버전을 조회합니다.
     * 상태 변경 횟수와 중복 UPDATE 여부를 검증합니다.
     * 첫 번째 정상 상태 변경 후에는 1이어야 합니다.
     */
    private long storedVersion() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId
        ));
    }

    /**
     * 테스트 시즌의 최종 수정 시각을 DB에서 조회합니다.
     * 중복 호출과 유효기간 전 요청이 값을 변경하지 않았는지 검증합니다.
     * SQL DATETIME(6) 값을 Timestamp로 읽습니다.
     */
    private Timestamp storedUpdatedAt() {
        return jdbc.queryForObject(
                "SELECT updated_at FROM seasons WHERE id = ?", Timestamp.class, seasonId
        );
    }

    /**
     * 공유 잠금 유지 스레드가 종료 신호를 기다립니다.
     * 타임아웃을 명시해 테스트가 무기한 정지하지 않도록 합니다.
     * 인터럽트는 복구해 테스트 실패로 전파합니다.
     *
     * @param latch 해제될 때까지 기다릴 동기화 신호
     */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("shared-lock test coordination timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("shared-lock test interrupted", exception);
        }
    }
}
