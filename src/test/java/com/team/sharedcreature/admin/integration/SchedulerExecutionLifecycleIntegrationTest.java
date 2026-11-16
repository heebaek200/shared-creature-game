
package com.team.sharedcreature.admin.integration;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerExecutionService;
import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduler 실행 상태 변경을 실제 MySQL에서 검증합니다.
 * Heartbeat, 완료, 실패, 재선점 및 오래된 실행권 차단을 확인합니다.
 * 테스트는 독립 트랜잭션의 실제 Commit 결과를 조회합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_USERNAME",
        matches = ".+"
)
class SchedulerExecutionLifecycleIntegrationTest {

    private static final Instant SCHEDULED_FOR =
            Instant.parse("2026-11-06T00:00:00Z");

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private SchedulerExecutionService claimService;

    @Autowired
    private SchedulerExecutionLifecycleService lifecycleService;

    @Autowired
    private SchedulerExecutionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String jobType;

    /**
     * 테스트마다 고유한 논리 작업을 생성합니다.
     * 다른 통합 테스트의 실행 이력과 충돌하지 않습니다.
     * 생성되는 식별자는 DB의 VARCHAR(50) 범위 안에 있습니다.
     */
    @BeforeEach
    void setUp() {
        jobType = "TEST_LIFE_"
                + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 각 테스트의 실행 이력을 삭제합니다.
     * 테스트 전용 작업 종류만 삭제 대상으로 지정합니다.
     * 운영 업무의 실행 이력은 변경하지 않습니다.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("""
                DELETE FROM scheduler_executions
                 WHERE job_type = ?
                """, jobType);
    }

    /**
     * Heartbeat가 Lease 만료 시각을 연장하는지 확인합니다.
     * 연장된 Lease는 DB에 Commit되어야 합니다.
     * Lease가 유효한 동안 다른 실행자는 선점하지 못합니다.
     */
    @Test
    void heartbeatExtendsLeaseInDatabase() {
        SchedulerClaimTicket ticket = claim("server-a");

        Instant before = load(ticket).getLeaseExpiresAt();

        // 기존 5분 Lease를 1시간 Lease로 연장합니다.
        lifecycleService.renewLease(
                ticket, Duration.ofHours(1)
        );

        Instant after = load(ticket).getLeaseExpiresAt();

        assertTrue(after.isAfter(before));
        assertTrue(claimService.tryClaim(
                jobType, SCHEDULED_FOR, "server-b", LEASE
        ).isEmpty());
    }

    /**
     * 정상 처리된 작업의 성공 기록을 검증합니다.
     * 결과 JSON과 완료 시각이 DB에 저장되어야 합니다.
     * SUCCEEDED 작업은 다시 선점할 수 없습니다.
     */
    @Test
    void succeededExecutionCannotRetry() {
        SchedulerClaimTicket ticket = claim("server-a");

        // 업무 Commit 이후의 성공 기록을 가정합니다.
        lifecycleService.markSucceeded(
                ticket, "{\"processed\":3}"
        );

        SchedulerExecution saved = load(ticket);

        assertEquals("SUCCEEDED", saved.getStatus());
        // JSON 문자열의 공백이나 직렬화 형식이 아닌 실제 값을 검증합니다.
        Integer processed = jdbcTemplate.queryForObject("""
        SELECT CAST(
                   JSON_UNQUOTE(JSON_EXTRACT(result_summary, '$.processed'))
                   AS UNSIGNED
               )
          FROM scheduler_executions
         WHERE id = ?
        """, Integer.class, ticket.executionId());

        assertEquals(3, processed);
        assertNotNull(saved.getCompletedAt());

        // 성공한 논리 작업은 중복 실행을 허용하지 않습니다.
        assertTrue(claimService.tryClaim(
                jobType, SCHEDULED_FOR, "server-b", LEASE
        ).isEmpty());
    }

    /**
     * 실패 상태 저장 후 재선점이 가능한지 검증합니다.
     * 실패 원인이 DB에 남아 있어야 합니다.
     * 재선점 시 시도 번호가 증가하고 실패 필드가 초기화됩니다.
     */
    @Test
    void failedExecutionCanRetry() {
        SchedulerClaimTicket first = claim("server-a");

        lifecycleService.markFailed(
                first, "TEMP_ERROR", "Temporary failure"
        );

        // 재시도 전에 실패 정보가 저장됐는지 검사합니다.
        SchedulerExecution failed = load(first);
        assertEquals("FAILED", failed.getStatus());
        assertEquals("TEMP_ERROR", failed.getErrorCode());
        assertNotNull(failed.getCompletedAt());

        SchedulerClaimTicket second = claim("server-b");

        // 같은 DB 행이 새로운 시도로 재사용됩니다.
        assertEquals(first.executionId(), second.executionId());
        assertEquals(2, second.attemptCount());
        assertEquals("RUNNING", load(second).getStatus());
        assertNull(load(second).getErrorCode());
    }

    /**
     * Lease를 상실한 이전 시도의 상태 변경을 거절합니다.
     * 동일 인스턴스가 다시 선점해도 시도 번호는 변경됩니다.
     * 과거 실행권의 완료·실패·Heartbeat 요청은 모두 실패해야 합니다.
     */
    @Test
    void staleAttemptCannotUpdateNewOwner() {
        SchedulerClaimTicket first = claim("server-a");
        expireLease(first);

        // 동일 인스턴스가 새 시도 번호로 다시 선점합니다.
        SchedulerClaimTicket second = claim("server-a");
        assertEquals(2, second.attemptCount());

        // 1번 시도의 실행권으로 2번 시도를 변경할 수 없습니다.
        assertThrows(IllegalStateException.class, () ->
                lifecycleService.markSucceeded(first, "{}")
        );
        assertThrows(IllegalStateException.class, () ->
                lifecycleService.markFailed(
                        first, "OLD_ERROR", "Stale attempt"
                )
        );
        assertThrows(IllegalStateException.class, () ->
                lifecycleService.renewLease(first, LEASE)
        );

        assertEquals("RUNNING", load(second).getStatus());
        assertEquals(2, load(second).getAttemptCount());

        // 올바른 실행권으로는 정상 완료가 가능합니다.
        lifecycleService.markSucceeded(second, "{}");
        assertEquals("SUCCEEDED", load(second).getStatus());
    }

    /**
     * 만료된 실행권의 성공 확정을 거절합니다.
     * 재선점 이전이라도 Lease 만료 이후에는 갱신할 수 없습니다.
     * 작업은 RUNNING으로 남아 이후 정상화에서 복구할 수 있어야 합니다.
     */
    @Test
    void expiredLeaseCannotComplete() {
        SchedulerClaimTicket ticket = claim("server-a");
        expireLease(ticket);

        // 만료된 실행권은 성공 상태를 기록할 수 없습니다.
        assertThrows(IllegalStateException.class, () ->
                lifecycleService.markSucceeded(ticket, "{}")
        );

        assertEquals("RUNNING", load(ticket).getStatus());

        // 다른 실행자는 만료된 행을 재선점할 수 있습니다.
        SchedulerClaimTicket next = claim("server-b");
        assertEquals(2, next.attemptCount());
    }

    /**
     * 테스트 전용 논리 작업의 실행권을 획득합니다.
     * 성공한 최초 선점의 Ticket을 반환합니다.
     * 후속 테스트는 이 Ticket으로 소유권을 검증합니다.
     */
    private SchedulerClaimTicket claim(String ownerInstanceId) {
        return claimService.tryClaim(
                jobType,
                SCHEDULED_FOR,
                ownerInstanceId,
                LEASE
        ).orElseThrow();
    }

    /**
     * 실행 이력을 DB에서 다시 조회합니다.
     * 독립 트랜잭션으로 반영된 최신 상태를 확인합니다.
     * 이전 실행권과 현재 실행권은 같은 행을 조회할 수 있습니다.
     */
    private SchedulerExecution load(SchedulerClaimTicket ticket) {
        return repository.findById(ticket.executionId())
                .orElseThrow();
    }

    /**
     * 이미 지나간 시각으로 Lease 만료 시각을 변경합니다.
     * 대기 시간에 의존하지 않고 만료 상황을 재현합니다.
     * SQL UPDATE는 실행권 복구 테스트에서만 사용합니다.
     */
    private void expireLease(SchedulerClaimTicket ticket) {
        jdbcTemplate.update("""
                UPDATE scheduler_executions
                   SET lease_expires_at = ?
                 WHERE id = ?
                """,
                Timestamp.from(Instant.now().minusSeconds(60)),
                ticket.executionId()
        );
    }
}
