package com.team.sharedcreature.payment.integration;

import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.payment.application.PaymentWebhookLeaseHeartbeat;
import com.team.sharedcreature.payment.application.PaymentWebhookRecoveryService;
import com.team.sharedcreature.payment.application.PaymentWebhookReconciliationNormalizationService;
import com.team.sharedcreature.payment.application.RefundRecoveryWork;
import com.team.sharedcreature.payment.application.RefundReconciliationNormalizationService;
import com.team.sharedcreature.payment.scheduler.RefundReconciliationScheduler;
import com.team.sharedcreature.quest.application.PhotoRetentionNormalizationService;
import com.team.sharedcreature.quest.application.PhotoRetentionWork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #24의 공통 Scheduler 실행권을 실제 MySQL에서 교차 검증합니다.
 * 사진/결제/환불 업무는 Mock으로 대체하고, 선점·Lease·실행 이력은 실제 DB를 사용합니다.
 * #22 환불 업무 미병합 상태에서도 실행 계층만 안전하게 검증할 수 있습니다.
 * 테스트 전용 DB의 spring.jpa.hibernate.ddl-auto=create-drop 구성을 전제로 합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.scheduler.enabled=false",
        "app.scheduler.photo-retention-enabled=false",
        "app.scheduler.payment-webhook-enabled=false",
        "app.scheduler.refund-reconciliation-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SchedulerCrossDomainMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:07:45Z");
    private static final Instant BUCKET = Instant.parse("2026-11-11T09:05:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PAYMENT = "PAYMENT_WEBHOOK_RECONCILIATION";
    private static final String PHOTO = "PHOTO_RETENTION_CLEANUP";
    private static final String REFUND = "REFUND_RECONCILIATION";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SchedulerJobRunner runner;
    @Autowired private SchedulerExecutionLifecycleService lifecycle;
    @Autowired private ApplicationContext applicationContext;

    private PaymentWebhookRecoveryService paymentWork;
    private RefundRecoveryWork refundWork;
    private PhotoRetentionWork photoWork;

    /**
     * 테스트마다 새로운 업무 Mock을 만들어 이전 호출 수를 초기화합니다.
     * Scheduler 실데이터는 별도 지정된 UTC 논리 구간만 삭제합니다.
     * 다른 프로젝트 업무 테이블과 결제 원장은 수정하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        // 운영 업무를 직접 실행하지 않도록 매번 새로운 Mock을 생성합니다.
        paymentWork = mock(PaymentWebhookRecoveryService.class);
        refundWork = mock(RefundRecoveryWork.class);
        photoWork = mock(PhotoRetentionWork.class);
        clearOwnExecutions();
    }

    /**
     * 테스트에서 만든 실행권 이력을 정리합니다.
     * 테스트 전용 MySQL 스키마에서만 호출하도록 환경 변수와 test 프로필로 제한합니다.
     * 실제 포트원 결제 원장과 Webhook 이벤트 행은 건드리지 않습니다.
     */
    @AfterEach
    void tearDown() {
        clearOwnExecutions();
    }

    /**
     * 결제 Webhook 성공 시 Runner가 실제 DB에 SUCCEEDED 이력을 Commit하는지 확인합니다.
     * result_summary는 원본 Webhook Payload가 아닌 숫자 집계 JSON이어야 합니다.
     * DB는 Mock하지 않고 트랜잭션 종료 이후 별도 JDBC 조회로 검사합니다.
     */
    @Test
    void paymentSuccessPersistsSummaryInMySql() {
        when(paymentWork.recoverStaleWebhooks()).thenReturn(paymentSummary(3, 2, 1, 0));

        // Scheduler는 사용자 API를 호출하지 않고 실제 실행권만 선점합니다.
        var result = paymentNormalizer(FIXED_CLOCK).normalize();

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        assertNull(result.error());
        assertEquals("SUCCEEDED", status(PAYMENT, BUCKET));
        assertEquals(1, attemptCount(PAYMENT, BUCKET));
        assertEquals(2, jsonCount(PAYMENT, BUCKET, "processedCount"));
        verify(paymentWork, times(1)).recoverStaleWebhooks();
    }

    /**
     * 동일 논리 구간의 성공 이력은 다른 Scheduler 인스턴스에도 재사용되지 않습니다.
     * 두 인스턴스의 ownerId는 다르지만 DB UNIQUE와 SUCCEEDED로 재선점이 막힙니다.
     * 같은 구간을 재호출해도 업무 복구는 한 번만 실행해야 합니다.
     */
    @Test
    void duplicatePaymentBucketIsSkippedAcrossInstances() {
        when(paymentWork.recoverStaleWebhooks()).thenReturn(paymentSummary(1, 1, 0, 0));
        var first = paymentNormalizer(FIXED_CLOCK).normalize();
        var second = paymentNormalizer(FIXED_CLOCK).normalize();

        // DB의 논리 작업 키가 실행 인스턴스를 넘어 중복을 막습니다.
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, first.execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, second.execution());
        assertEquals(1, rowCount(PAYMENT, BUCKET));
        verify(paymentWork, times(1)).recoverStaleWebhooks();
    }

    /**
     * 개별 Webhook 실패가 존재하면 해당 구간은 성공으로 기록할 수 없습니다.
     * 같은 논리 구간에서 다음 재시도는 기존 FAILED 행을 재선점해야 합니다.
     * 재시도는 이미 처리된 결제 원장을 직접 변경하지 않고 #21 업무에 위임합니다.
     */
    @Test
    void failedPaymentSummaryCanBeRetriedInSameBucket() {
        when(paymentWork.recoverStaleWebhooks()).thenReturn(
                paymentSummary(2, 1, 0, 1),
                paymentSummary(1, 1, 0, 0)
        );
        var first = paymentNormalizer(FIXED_CLOCK).normalize();
        assertNotNull(first.error());
        assertEquals("FAILED", status(PAYMENT, BUCKET));

        // 기존 FAILED 행이 attempt_count=2로 갱신되어야 합니다.
        var second = paymentNormalizer(FIXED_CLOCK).normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, second.execution());
        assertEquals("SUCCEEDED", status(PAYMENT, BUCKET));
        assertEquals(2, attemptCount(PAYMENT, BUCKET));
        assertEquals(1, rowCount(PAYMENT, BUCKET));
    }

    /**
     * 배치 중 DB 장애 예외가 발생하면 FAILED가 실제 저장되어야 합니다.
     * 장애가 해소된 뒤 같은 논리 구간을 다시 호출하면 성공 처리되어야 합니다.
     * 실패를 정상 SKIPPED나 SUCCEEDED로 덮어쓰면 안 됩니다.
     */
    @Test
    void databaseFailureIsRecordedThenRetried() {
        when(paymentWork.recoverStaleWebhooks()).thenThrow(
                new DataAccessResourceFailureException("simulated payment query outage")
        ).thenReturn(paymentSummary(0, 0, 0, 0));

        // 실제 데이터베이스의 실행 이력만 사용하며, 오류는 Mock 업무에서 발생시킵니다.
        assertNotNull(paymentNormalizer(FIXED_CLOCK).normalize().error());
        assertEquals("FAILED", status(PAYMENT, BUCKET));
        assertEquals("SUCCEEDED", statusAfterPaymentRetry());
        assertEquals(2, attemptCount(PAYMENT, BUCKET));
    }

    /**
     * 사진·결제는 같은 DB 테이블을 사용하지만 Job Type으로 구분합니다.
     * 같은 5분 구간에서 하나가 실패해도 다른 업무는 성공할 수 있어야 합니다.
     * 서로의 FAILED 기록을 재선점하거나 덮어쓰지 않습니다.
     */
    @Test
    void independentPhotoAndPaymentJobsDoNotShareFailureState() {
        when(photoWork.cleanup(eq(NOW), any(Runnable.class)))
                .thenReturn(new PhotoRetentionWork.Summary(0, 0, 0, 1, false));
        when(paymentWork.recoverStaleWebhooks()).thenReturn(paymentSummary(1, 1, 0, 0));

        // 사진 정리의 단건 실패는 결제 Webhook 실행을 차단하면 안 됩니다.
        assertNotNull(photoNormalizer().normalize().error());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                paymentNormalizer(FIXED_CLOCK).normalize().execution());
        assertEquals("FAILED", status(PHOTO, BUCKET));
        assertEquals("SUCCEEDED", status(PAYMENT, BUCKET));
        assertEquals(2, rowCountAllJobsAt(BUCKET));
    }

    /**
     * 환불 PG 확정 실패는 업무 결과이므로 Scheduler 자체는 SUCCEEDED로 기록합니다.
     * 이 테스트의 환불 업무는 Mock이며 실제 취소나 아이템 회수를 실행하지 않습니다.
     * #22의 환불 상태 전이 검증과 구분해서 판단해야 합니다.
     */
    @Test
    void definitiveRefundFailureIsBusinessOutcomeNotJobFailure() {
        when(refundWork.recoverStaleRefunds(any(Runnable.class)))
                .thenReturn(refundSummary(2, 1, 1, 0));

        // 완료 1건과 PG 확정 실패 1건 모두 정상 집계로 처리합니다.
        var result = refundNormalizer().normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        assertEquals("SUCCEEDED", status(REFUND, BUCKET));
        assertEquals(1, jsonCount(REFUND, BUCKET, "definitiveFailedCount"));
    }

    /**
     * 환불 업무의 기술 오류는 기존 실행권에 FAILED로 남겨야 합니다.
     * 다음 호출은 같은 Job Type과 논리 시각의 행을 재선점합니다.
     * 복구 업무가 Mock이므로 PG 취소 재요청은 수행하지 않습니다.
     */
    @Test
    void refundTechnicalErrorIsRetriedAndCountsAttempts() {
        when(refundWork.recoverStaleRefunds(any(Runnable.class))).thenReturn(
                refundSummary(1, 0, 0, 1),
                refundSummary(1, 1, 0, 0)
        );

        // 최초 기술 오류는 건수로 보고되며 FAILED가 기록됩니다.
        assertNotNull(refundNormalizer().normalize().error());
        assertEquals("FAILED", status(REFUND, BUCKET));
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, refundNormalizer().normalize().execution());
        assertEquals("SUCCEEDED", status(REFUND, BUCKET));
        assertEquals(2, attemptCount(REFUND, BUCKET));
    }

    /**
     * 환불 업무에 전달된 Heartbeat가 실제 실행권 행을 갱신할 수 있는지 검증합니다.
     * 이전 시도나 다른 인스턴스의 티켓을 사용하지 않습니다.
     * #22 담당 구현에서도 처리 중 반복 호출해야 하는 콜백 계약입니다.
     */
    @Test
    void refundHeartbeatUsesRealLeaseLifecycle() {
        when(refundWork.recoverStaleRefunds(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable heartbeat = invocation.getArgument(0);
            // 실제 DB의 Lease만 갱신합니다. 복구 대상은 없으므로 집계도 모두 0입니다.
            heartbeat.run();
            return refundSummary(0, 0, 0, 0);
        });

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, refundNormalizer().normalize().execution());
        assertEquals("SUCCEEDED", status(REFUND, BUCKET));
        assertNotNull(jdbc.queryForObject("""
                SELECT heartbeat_at
                  FROM scheduler_executions
                 WHERE job_type = ?
                   AND scheduled_for = ?
                """, Timestamp.class, REFUND, Timestamp.from(BUCKET)));
    }

    /**
     * 서로 다른 5분 논리 구간은 개별 Scheduler 실행 이력을 가져야 합니다.
     * 오래된 구간에서 SUCCEEDED였더라도 다음 구간의 실행을 차단하지 않습니다.
     * 결제/환불 원장 상태가 동일한지는 실제 업무 서비스가 판단합니다.
     */
    @Test
    void nextUtcBucketGetsDistinctExecutionRow() {
        when(paymentWork.recoverStaleWebhooks()).thenReturn(paymentSummary(0, 0, 0, 0));
        var first = paymentNormalizer(FIXED_CLOCK).normalize();
        var second = paymentNormalizer(Clock.fixed(NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC))
                .normalize();

        // 과거 완료 행이 다음 5분 경계의 새로운 작업을 차단하지 않습니다.
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, first.execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, second.execution());
        assertEquals(1, rowCount(PAYMENT, BUCKET));
        assertEquals(1, rowCount(PAYMENT, BUCKET.plus(Duration.ofMinutes(5))));
        verify(paymentWork, times(2)).recoverStaleWebhooks();
    }

    /**
     * 두 인스턴스가 같은 실행 시각에 진입해도 하나만 업무를 수행해야 합니다.
     * 첫 실행을 Mock 내부에서 붙잡고 두 번째 정상화 서비스로 선점을 시도합니다.
     * 같은 프로세스 AtomicBoolean이 아닌 실제 MySQL 제약으로 확인합니다.
     */
    @Test
    @Timeout(20)
    void concurrentPaymentInstancesProcessCurrentBucketOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(paymentWork.recoverStaleWebhooks()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("integration test barrier timeout");
            }
            return paymentSummary(1, 1, 0, 0);
        });
        FutureTask<PaymentWebhookReconciliationNormalizationService.Result> firstTask =
                new FutureTask<>(() -> paymentNormalizer(FIXED_CLOCK).normalize());
        Thread thread = new Thread(firstTask, "payment-scheduler-mysql-test");
        thread.start();

        try {
            // 첫 실행이 DB 실행권을 선점하고 업무를 시작한 다음 두 번째를 호출합니다.
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            var second = paymentNormalizer(FIXED_CLOCK).normalize();
            assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, second.execution());
        } finally {
            release.countDown();
        }
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                firstTask.get(8, TimeUnit.SECONDS).execution());
        thread.join(1_000);
        assertEquals(1, rowCount(PAYMENT, BUCKET));
        verify(paymentWork, times(1)).recoverStaleWebhooks();
    }

    /**
     * test 프로필에서는 환불 Scheduler 자동 실행 Bean이 만들어지지 않아야 합니다.
     * #22 실제 환불 서비스가 없어도 테스트용 Spring Context는 기동해야 합니다.
     * 환불 정상화 서비스는 이번 테스트에서 명시적으로 생성해 기능만 검증합니다.
     */
    @Test
    void refundAutoSchedulerIsDisabledInTestProfile() {
        // 테스트 도중 PG 취소/결제 Cron이 돌지 않아야 합니다.
        assertTrue(applicationContext.getBeansOfType(RefundReconciliationScheduler.class).isEmpty());
    }

    /**
     * Webhook 정상화 서비스에 실DB Runner와 Mock 업무를 연결합니다.
     * 같은 Clock으로 서로 다른 서비스 인스턴스를 만들 수 있도록 별도 팩토리를 둡니다.
     * PortOne 최신 조회 자체는 #21이 제공하므로 이 테스트에서 실행하지 않습니다.
     */
    private PaymentWebhookReconciliationNormalizationService paymentNormalizer(Clock clock) {
        return new PaymentWebhookReconciliationNormalizationService(
                runner, paymentWork, new PaymentWebhookLeaseHeartbeat(lifecycle), clock);
    }

    /**
     * 환불 정상화 서비스에 실제 DB 실행권과 Mock 환불 업무를 연결합니다.
     * #22 구현 Bean이 아직 없으므로 DI 검증 대신 수동 인스턴스로 실행합니다.
     * 4-I-2B에서는 실제 환불 복구 서비스와 결합해 별도 테스트해야 합니다.
     */
    private RefundReconciliationNormalizationService refundNormalizer() {
        return new RefundReconciliationNormalizationService(runner, lifecycle, refundWork, FIXED_CLOCK);
    }

    /**
     * 사진 정상화에 이미 검증된 4-H-2 업무 계약과 실제 Runner를 연결합니다.
     * 파일 삭제는 4-H-2에서 검증했으므로 여기서는 Mock으로 통제합니다.
     * 세 Job Type이 동일한 Scheduler 실행권 테이블에서 독립적인지 확인합니다.
     */
    private PhotoRetentionNormalizationService photoNormalizer() {
        return new PhotoRetentionNormalizationService(runner, lifecycle, photoWork, FIXED_CLOCK);
    }

    /**
     * #21 Webhook 복구 서비스가 반환하는 결과 레코드를 생성합니다.
     * 선택/처리/건너뜀/실패 건수 합계는 반드시 일치하도록 호출자가 관리합니다.
     * 실제 지급과 결제 거래 이력은 이 객체에서 생성되지 않습니다.
     */
    private PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult paymentSummary(
            int selected, int processed, int skipped, int failed) {
        return new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(
                selected, processed, skipped, failed);
    }

    /**
     * #22 환불 어댑터 대신 Mock에서 반환할 결과를 만듭니다.
     * 완료/PG 확정 실패/기술 오류를 구분하는 최소 예제만 사용합니다.
     * pending/unknown/skipped는 별도의 실제 환불 업무 테스트에서 다룹니다.
     */
    private RefundRecoveryWork.RecoverySummary refundSummary(
            int selected, int completed, int definitiveFailed, int errors) {
        return new RefundRecoveryWork.RecoverySummary(
                selected, completed, definitiveFailed, 0, 0, 0, errors);
    }

    /**
     * 실패 후 재시도하여 실제 DB에 SUCCEEDED가 남는지 확인합니다.
     * 명명된 보조 메서드로 Assertion 내 상태 변경을 분리합니다.
     * 재시도는 직전 FAILED 행의 시도 번호를 증가시킵니다.
     */
    private String statusAfterPaymentRetry() {
        var retry = paymentNormalizer(FIXED_CLOCK).normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, retry.execution());
        return status(PAYMENT, BUCKET);
    }

    /**
     * 테스트 대상 5분 구간의 실제 Scheduler 행 상태를 읽습니다.
     * 실패·성공 전이와 실행권이 DB에 Commit되었는지 확인합니다.
     * 부재 시 JdbcTemplate 예외가 발생해 테스트가 실패합니다.
     */
    private String status(String jobType, Instant bucket) {
        return jdbc.queryForObject("""
                SELECT status
                  FROM scheduler_executions
                 WHERE job_type = ?
                   AND scheduled_for = ?
                """, String.class, jobType, Timestamp.from(bucket));
    }

    /**
     * 실패 이후 재선점한 시도 횟수를 검증합니다.
     * 최초 성공은 1, FAILED 재시도 후 성공은 2가 되어야 합니다.
     * 새 Scheduler 행을 추가하지 않고 같은 키의 행을 재사용해야 합니다.
     */
    private int attemptCount(String jobType, Instant bucket) {
        return jdbc.queryForObject("""
                SELECT attempt_count
                  FROM scheduler_executions
                 WHERE job_type = ?
                   AND scheduled_for = ?
                """, Integer.class, jobType, Timestamp.from(bucket));
    }

    /**
     * Job Type과 논리 구간의 UNIQUE 기준 행 개수를 확인합니다.
     * 값이 1을 초과하면 동일 구간 중복 실행 방어가 깨진 상태입니다.
     * test 프로필의 자동 Cron이 비활성화된 상태에서 검사합니다.
     */
    private int rowCount(String jobType, Instant bucket) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM scheduler_executions
                 WHERE job_type = ?
                   AND scheduled_for = ?
                """, Integer.class, jobType, Timestamp.from(bucket));
    }

    /**
     * 같은 UTC 구간에 서로 다른 Scheduler Job Type 이력이 존재하는지 확인합니다.
     * 사진과 Webhook이 같은 시간을 사용해도 DB UNIQUE 키는 중복되지 않습니다.
     * 실제 파일·PG 처리 결과는 이 테스트의 관심사가 아닙니다.
     */
    private int rowCountAllJobsAt(Instant bucket) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM scheduler_executions
                 WHERE job_type IN (?, ?, ?)
                   AND scheduled_for = ?
                """, Integer.class, PAYMENT, PHOTO, REFUND, Timestamp.from(bucket));
    }

    /**
     * Scheduler 실행 요약 JSON에서 해당 정수 값을 추출합니다.
     * MySQL JSON 타입에 실제 저장된 결과를 읽기 때문에 문자열만 검사하는 테스트보다 강합니다.
     * 잘못된 JSON으로 Commit되었다면 JSON_EXTRACT 호출에서 실패합니다.
     */
    private int jsonCount(String jobType, Instant bucket, String field) {
        // JSON 경로는 테스트 코드의 고정 필드명만 사용합니다.
        return jdbc.queryForObject("""
                SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(result_summary, ?)) AS UNSIGNED)
                  FROM scheduler_executions
                 WHERE job_type = ?
                   AND scheduled_for = ?
                """, Integer.class, "$." + field, jobType, Timestamp.from(bucket));
    }

    /**
     * 해당 테스트 클래스의 전용 논리 구간에 한해서만 실행권 이력을 삭제합니다.
     * 다른 Job Type과 다른 시간의 기존 실행권은 전혀 삭제하지 않습니다.
     * 운영 DB에서 호출하지 말고 테스트 전용 MySQL만 사용해야 합니다.
     */
    private void clearOwnExecutions() {
        jdbc.update("""
                DELETE FROM scheduler_executions
                 WHERE job_type IN (?, ?, ?)
                   AND scheduled_for >= ?
                   AND scheduled_for < ?
                """, PAYMENT, PHOTO, REFUND,
                Timestamp.from(BUCKET), Timestamp.from(BUCKET.plus(Duration.ofMinutes(15))));
    }
}
