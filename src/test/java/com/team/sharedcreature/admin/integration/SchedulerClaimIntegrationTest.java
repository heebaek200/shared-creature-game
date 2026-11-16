
package com.team.sharedcreature.admin.integration;

import com.team.sharedcreature.admin.application.SchedulerExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JPA 기반 Scheduler 선점을 실제 MySQL에서 검증합니다.
 * UNIQUE 충돌, 동시 선점 및 기존 실행 재선점을 확인합니다.
 * 테스트 메서드는 트랜잭션으로 감싸지 않아 독립 Commit을 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_USERNAME",
        matches = ".+"
)
class SchedulerClaimIntegrationTest {

    private static final Instant SCHEDULED_FOR =
            Instant.parse("2026-11-06T00:00:00Z");

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private SchedulerExecutionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String jobType;

    /**
     * 테스트마다 고유한 Scheduler 작업 이름을 만듭니다.
     * 병렬 테스트와 다른 개발자의 데이터에 간섭하지 않습니다.
     * 실제 업무용 jobType을 사용하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        jobType = "TEST_CLAIM_"
                + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 테스트가 생성한 실행 이력만 삭제합니다.
     * 다른 Scheduler 이력에는 영향을 주지 않습니다.
     * 독립 트랜잭션으로 Commit한 테스트 행을 정리합니다.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("""
                DELETE FROM scheduler_executions
                 WHERE job_type = ?
                """, jobType);
    }

    /**
     * 동일 논리 작업을 연속 선점하는 상황을 검증합니다.
     * 첫 호출만 성공하고 두 번째 호출은 거절돼야 합니다.
     * DB에는 해당 실행 이력이 한 행만 존재해야 합니다.
     */
    @Test
    void duplicateClaimReturnsSingleOwner() {
        var first = service.tryClaim(
                jobType, SCHEDULED_FOR, "server-a", LEASE
        );

        // 이미 실행권이 유효하므로 두 번째 요청은 거절됩니다.
        var second = service.tryClaim(
                jobType, SCHEDULED_FOR, "server-b", LEASE
        );

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());
        assertEquals(1, first.orElseThrow().attemptCount());
        assertEquals(1L, executionCount());
    }

    /**
     * 서로 다른 스레드의 최초 INSERT 경합을 검증합니다.
     * UNIQUE 충돌 후 기존 행을 다시 조회하는 경로도 포함됩니다.
     * 정확히 하나의 실행자만 실행권을 가져야 합니다.
     *
     * @throws Exception 스레드 실행 또는 결과 수집 실패
     */
    @Test
    void concurrentClaimAllowsOnlyOneOwner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);

        try {
            var first = executor.submit(() -> {
                start.await();

                // 첫 번째 실행자가 동시에 선점을 시도합니다.
                return service.tryClaim(
                        jobType, SCHEDULED_FOR, "server-a", LEASE
                ).isPresent();
            });

            var second = executor.submit(() -> {
                start.await();

                // 두 번째 실행자도 같은 UNIQUE 키를 요청합니다.
                return service.tryClaim(
                        jobType, SCHEDULED_FOR, "server-b", LEASE
                ).isPresent();
            });

            start.countDown();

            boolean firstWon = first.get(15, TimeUnit.SECONDS);
            boolean secondWon = second.get(15, TimeUnit.SECONDS);

            // 성공한 실행자가 반드시 한 명이어야 합니다.
            assertEquals(
                    1,
                    (firstWon ? 1 : 0) + (secondWon ? 1 : 0)
            );
            assertEquals(1L, executionCount());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * FAILED 상태의 기존 실행을 다시 선점할 수 있는지 검증합니다.
     * 실패 상태는 이 단계에서 SQL로 직접 준비합니다.
     * 실제 실패 기록 Service는 STEP 3-B-2에서 구현합니다.
     */
    @Test
    void failedExecutionCanBeReclaimed() {
        service.tryClaim(
                jobType, SCHEDULED_FOR, "server-a", LEASE
        ).orElseThrow();

        // 이전 실행이 실패했다고 가정합니다.
        jdbcTemplate.update("""
                UPDATE scheduler_executions
                   SET status = 'FAILED'
                     , completed_at = UTC_TIMESTAMP(6)
                 WHERE job_type = ?
                """, jobType);

        var reclaimed = service.tryClaim(
                jobType, SCHEDULED_FOR, "server-b", LEASE
        ).orElseThrow();

        // 새 실행자와 증가한 시도 번호를 확인합니다.
        assertEquals("server-b", reclaimed.ownerInstanceId());
        assertEquals(2, reclaimed.attemptCount());
        assertEquals(1L, executionCount());
    }

    /**
     * Lease가 만료된 RUNNING 실행의 복구를 검증합니다.
     * 재기동 상황을 재현하기 위해 DB의 만료 시각을 변경합니다.
     * 신규 행을 만들지 않고 기존 행을 새 시도로 갱신해야 합니다.
     */
    @Test
    void expiredLeaseCanBeReclaimed() {
        service.tryClaim(
                jobType, SCHEDULED_FOR, "server-a", LEASE
        ).orElseThrow();

        // 이전 실행자의 Lease를 과거 시각으로 변경합니다.
        jdbcTemplate.update("""
                UPDATE scheduler_executions
                   SET lease_expires_at = ?
                 WHERE job_type = ?
                """,
                Timestamp.from(Instant.now().minusSeconds(60)),
                jobType
        );

        var reclaimed = service.tryClaim(
                jobType, SCHEDULED_FOR, "server-b", LEASE
        ).orElseThrow();

        // 동일한 실행 행을 재사용해야 합니다.
        assertEquals(2, reclaimed.attemptCount());
        assertEquals("server-b", reclaimed.ownerInstanceId());
        assertEquals(1L, executionCount());
    }

    /**
     * 테스트 전용 실행 이력의 행 수를 조회합니다.
     * JPA 캐시가 아닌 DB 실제 저장 상태를 확인합니다.
     * UNIQUE 제약을 통한 단일 행 보존을 검증합니다.
     *
     * @return 해당 테스트 작업의 행 수
     */
    private long executionCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM scheduler_executions
                 WHERE job_type = ?
                """, Long.class, jobType);
    }
}
