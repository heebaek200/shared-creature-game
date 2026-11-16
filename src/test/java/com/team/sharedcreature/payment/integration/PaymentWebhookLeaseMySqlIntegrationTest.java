package com.team.sharedcreature.payment.integration;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerExecutionService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.payment.application.PaymentWebhookLeaseHeartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Heartbeat가 실제 MySQL Lease를 연장하고 중복 선점을 방지하는지 확인합니다.
 * PortOne을 호출하지 않으며 Scheduler 실행 이력에만 테스트 행을 생성합니다.
 * TEST_DB_USERNAME 환경변수가 있는 테스트 전용 DB에서만 실행합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class PaymentWebhookLeaseMySqlIntegrationTest {

    private static final String JOB_TYPE = "PAYMENT_WEBHOOK_J3_TEST";
    private final String ownerId = "webhook-j3-" + UUID.randomUUID();
    private Instant scheduledFor;

    @Autowired private SchedulerJobRunner runner;
    @Autowired private SchedulerExecutionService claims;
    @Autowired private SchedulerExecutionLifecycleService lifecycle;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 테스트용 논리 구간을 고유하게 지정합니다.
     * 실제 결제 복구 Job Type과 분리해 운영 이력을 오염시키지 않습니다.
     * 이전 테스트가 남긴 이력과 구분되도록 현재 초 단위 시각을 사용합니다.
     */
    @BeforeEach
    void setUp() {
        scheduledFor = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("DELETE FROM scheduler_executions WHERE job_type = ? AND scheduled_for = ?",
                JOB_TYPE, Timestamp.from(scheduledFor));
    }

    /**
     * 이 테스트가 만든 Scheduler 이력만 삭제합니다.
     * 결제·환불·Webhook 원장에는 접근하지 않습니다.
     * 사용자 데이터가 포함된 개발 DB에서는 실행하지 마세요.
     */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM scheduler_executions WHERE job_type = ? AND scheduled_for = ?",
                JOB_TYPE, Timestamp.from(scheduledFor));
    }

    /**
     * 업무가 초기 Lease보다 오래 실행되어도 주기적으로 갱신되어야 합니다.
     * 갱신 중 다른 소유자가 같은 논리 구간을 재선점할 수 없어야 합니다.
     * 실제 MySQL의 Heartbeat와 최종 SUCCEEDED를 함께 조회합니다.
     */
    @Test
    @Timeout(20)
    void heartbeatMaintainsLeaseBeyondInitialExpiry() {
        Duration lease = Duration.ofSeconds(2);
        var heartbeats = new PaymentWebhookLeaseHeartbeat(lifecycle, Duration.ofMillis(150));

        var result = runner.run(JOB_TYPE, scheduledFor, ownerId, lease,
                (Function<SchedulerClaimTicket, String>) ticket -> {
                    try (var scope = heartbeats.start(ticket, lease)) {
                        // 2초 초기 Lease를 넘기도록 PG 지연을 시뮬레이션합니다.
                        sleep(Duration.ofMillis(2_600));
                        assertTrue(claims.tryClaim(JOB_TYPE, scheduledFor, "competitor", lease).isEmpty(),
                                "Heartbeat가 살아 있다면 기존 구간 실행권은 재선점할 수 없습니다.");
                        scope.assertHealthy();
                        return "{\"heartbeatVerified\":true}";
                    }
                });

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result);
        var timestamps = jdbc.queryForMap("""
                SELECT started_at, heartbeat_at, completed_at, status, attempt_count
                  FROM scheduler_executions
                 WHERE job_type = ?
                   AND scheduled_for = ?
                """, JOB_TYPE, Timestamp.from(scheduledFor));
        assertEquals("SUCCEEDED", timestamps.get("status"));
        assertEquals(1, ((Number) timestamps.get("attempt_count")).intValue());
        assertTrue(((LocalDateTime) timestamps.get("heartbeat_at"))
                .isAfter((LocalDateTime) timestamps.get("started_at")));
        assertNotNull(timestamps.get("completed_at"));
    }

    /**
     * 지정한 시간만큼 실제 업무가 블로킹되는 상태를 재현합니다.
     * 인터럽트는 복원하고 테스트 실패로 전달합니다.
     * PG 요청이나 DB 트랜잭션을 이 메서드에서 생성하지 않습니다.
     */
    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 대기 중 인터럽트", exception);
        }
    }
}
