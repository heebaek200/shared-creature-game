
package com.team.sharedcreature.admin.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerExecutionTest {

    private static final Instant NOW =
            Instant.parse("2026-11-06T00:00:00Z");

    private static final Duration LEASE =
            Duration.ofMinutes(1);

    /**
     * 신규 작업의 초기 상태를 검증합니다.
     * 첫 시도는 RUNNING이며 시도 횟수는 1입니다.
     * Lease 만료 시각도 정확히 설정되어야 합니다.
     */
    @Test
    void newExecutionStartsRunning() {
        SchedulerExecution execution = createExecution();

        // 신규 실행의 주요 상태를 확인합니다.
        assertEquals("RUNNING", execution.getStatus());
        assertEquals(1, execution.getAttemptCount());
        assertEquals(NOW.plus(LEASE), execution.getLeaseExpiresAt());
        assertNull(execution.getCompletedAt());
    }

    /**
     * 아직 Lease가 유효한 작업의 재선점을 차단합니다.
     * 실행 중인 작업은 동일한 논리 키로 중복 실행하면 안 됩니다.
     * 재선점 실패 후 기존 상태도 유지되어야 합니다.
     */
    @Test
    void activeRunningCannotBeReclaimed() {
        SchedulerExecution execution = createExecution();

        assertFalse(execution.canReclaim(NOW.plusSeconds(30)));

        // 다른 실행자의 재선점을 거절합니다.
        assertThrows(IllegalStateException.class, () ->
                execution.reclaim(
                        "server-b",
                        NOW.plusSeconds(30),
                        LEASE
                )
        );

        assertEquals(1, execution.getAttemptCount());
    }

    /**
     * Lease가 정확히 만료되는 시각에 재선점을 허용합니다.
     * 새 실행자는 시도 번호를 증가시켜 실행합니다.
     * 이전 실행의 완료 기록은 새 시도로 이동하지 않습니다.
     */
    @Test
    void expiredRunningCanBeReclaimed() {
        SchedulerExecution execution = createExecution();
        Instant expiredAt = NOW.plus(LEASE);

        // 만료 경계 시각부터 새 실행권을 발급합니다.
        assertTrue(execution.canReclaim(expiredAt));
        execution.reclaim("server-b", expiredAt, LEASE);

        assertEquals("RUNNING", execution.getStatus());
        assertEquals("server-b", execution.getOwnerInstanceId());
        assertEquals(2, execution.getAttemptCount());
    }

    /**
     * 실패한 작업은 다음 시도에서 복구할 수 있습니다.
     * 실패 기록은 재선점 전까지 보존됩니다.
     * 재선점 후에는 현재 시도의 오류 필드를 초기화합니다.
     */
    @Test
    void failedExecutionCanRetry() {
        SchedulerExecution execution = createExecution();

        execution.markFailed(
                "server-a", 1, NOW.plusSeconds(10),
                "TEMPORARY_ERROR", "Temporary failure"
        );

        assertEquals("FAILED", execution.getStatus());
        assertTrue(execution.canReclaim(NOW.plusSeconds(11)));

        // 새 시도로 재시작하면서 오류 정보를 비웁니다.
        execution.reclaim("server-b", NOW.plusSeconds(11), LEASE);

        assertEquals(2, execution.getAttemptCount());
        assertNull(execution.getErrorCode());
        assertNull(execution.getCompletedAt());
    }

    /**
     * 성공한 Scheduler 작업은 재실행하지 않습니다.
     * Lease가 만료된 시각 이후에도 성공 상태를 유지합니다.
     * 완료 결과는 다음 트리거에서 중복 반영하면 안 됩니다.
     */
    @Test
    void succeededExecutionCannotBeReclaimed() {
        SchedulerExecution execution = createExecution();

        execution.markSucceeded(
                "server-a", 1, NOW.plusSeconds(10),
                "{\"processed\":3}"
        );

        // 시간이 지나도 성공한 작업은 재선점하지 않습니다.
        assertEquals("SUCCEEDED", execution.getStatus());
        assertFalse(execution.canReclaim(NOW.plusSeconds(120)));
        assertEquals("{\"processed\":3}", execution.getResultSummary());
    }

    /**
     * 이전 시도의 지연 완료를 차단합니다.
     * 같은 인스턴스가 Lease를 재선점할 수도 있습니다.
     * 인스턴스 ID뿐 아니라 시도 번호까지 비교해야 합니다.
     */
    @Test
    void staleAttemptCannotCompleteNewAttempt() {
        SchedulerExecution execution = createExecution();
        Instant expiredAt = NOW.plus(LEASE);

        // 같은 인스턴스가 새로운 시도로 재선점합니다.
        execution.reclaim("server-a", expiredAt, LEASE);

        assertThrows(IllegalStateException.class, () ->
                execution.markSucceeded(
                        "server-a", 1,
                        expiredAt.plusSeconds(1),
                        "{}"
                )
        );

        assertEquals("RUNNING", execution.getStatus());
        assertEquals(2, execution.getAttemptCount());
    }

    /**
     * Heartbeat가 실행권 유효 기간을 연장하는지 검증합니다.
     * 현재 시도의 소유자만 Lease를 연장할 수 있습니다.
     * 연장 전 만료 시각에는 더 이상 재선점되면 안 됩니다.
     */
    @Test
    void heartbeatExtendsLease() {
        SchedulerExecution execution = createExecution();

        // 30초 시점에 Lease를 다시 1분 연장합니다.
        execution.renewLease(
                "server-a", 1,
                NOW.plusSeconds(30), LEASE
        );

        assertEquals(
                NOW.plusSeconds(90),
                execution.getLeaseExpiresAt()
        );
        assertFalse(execution.canReclaim(NOW.plusSeconds(60)));
    }

    /**
     * 만료된 Lease로는 작업을 완료할 수 없습니다.
     * 다른 실행자가 아직 재선점하지 않았더라도 동일합니다.
     * 만료된 작업은 새 시도로 회복해야 합니다.
     */
    @Test
    void expiredLeaseCannotMarkSucceeded() {
        SchedulerExecution execution = createExecution();

        // 만료 경계에서는 성공 확정을 거절합니다.
        assertThrows(IllegalStateException.class, () ->
                execution.markSucceeded(
                        "server-a", 1,
                        NOW.plus(LEASE), "{}"
                )
        );

        assertEquals("RUNNING", execution.getStatus());
    }

    /**
     * 테스트에 사용할 동일한 신규 실행을 생성합니다.
     * 모든 테스트에서 같은 논리 실행 시각을 사용합니다.
     * DB 없이 도메인 상태 전이를 검증하기 위한 Fixture입니다.
     */
    private SchedulerExecution createExecution() {
        return SchedulerExecution.startNew(
                "QUEST_DAILY",
                NOW,
                "server-a",
                NOW,
                LEASE
        );
    }
}
