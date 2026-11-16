package com.team.sharedcreature.payment.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.domain.AvatarSlot;
import com.team.sharedcreature.avatar.domain.ShopItemStatus;
import com.team.sharedcreature.payment.application.*;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.scheduler.RefundReconciliationScheduler;
import com.team.sharedcreature.season.scheduler.SeasonCloseScheduler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** local 기본 설정으로 실제 기동 이벤트와 다음 UTC 5분 Cron의 DB 복구를 확인합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://localhost:3306/shared_creature_game_test?serverTimezone=UTC&characterEncoding=UTF-8}",
        "spring.datasource.username=${TEST_DB_USERNAME:}",
        "spring.datasource.password=${TEST_DB_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=update", "spring.jpa.show-sql=false", "spring.sql.init.mode=never",
        "app.scheduler.photo-retention-enabled=false", "app.scheduler.payment-webhook-enabled=false",
        "portone.store-id=refund-startup-test", "logging.level.com.team.sharedcreature=INFO"
})
// 개발 중 반복 실행 시간을 줄이기 위해 실제 5분 Cron 검증만 임시로 제외합니다.
@Disabled("실제 5분 Cron 대기 테스트 임시 제외: 최종 Scheduler 검증 시 다시 활성화")
@ActiveProfiles("local")
@ContextConfiguration(initializers = RefundSchedulerLocalStartupIntegrationTest.TestDatabaseOnly.class)
@Import(RefundSchedulerLocalStartupIntegrationTest.GuardConfiguration.class)
// 프로필별 검증이 끝나면 자동 Scheduler도 종료하여 다음 테스트와 겹치지 않게 합니다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundSchedulerLocalStartupIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RefundApprovalTransactionService approval;
    @Autowired private RefundResultTransactionService results;
    @Autowired private RunState state;
    @MockitoBean private RefundGateway gateway;
    @MockitoBean private RefundLookupGateway lookup;
    // 다른 업무의 기동·정기 실행은 이 테스트에서 수행하지 않습니다.
    @MockitoBean private SeasonCloseScheduler seasonScheduler;
    private Fixture fixture;
    private Instant nextSlot;

    @Test
    @Timeout(value = 330, unit = TimeUnit.SECONDS)
    void 기본활성화로_기동실행후_실제_다음5분Cron이_환불을_복구한다() throws Exception {
        assertThat(context.getEnvironment().getProperty("app.scheduler.refund-reconciliation-enabled", Boolean.class)).isTrue();
        assertThat(context.getBeansOfType(RefundRecoveryWork.class)).hasSize(1);
        assertThat(context.getBeansOfType(RefundReconciliationScheduler.class)).hasSize(1);
        assertThat(jobStatus(state.startupSlot)).isEqualTo("SUCCEEDED");

        fixture = new TransactionTemplate(transactionManager).execute(tx -> {
            Instant now = Instant.now();
            var user = User.register("SUPER_ADMIN", now);
            em.persist(user);
            var shop = BeanUtils.instantiateClass(ShopItem.class);
            set(shop, "code", "startup-" + UUID.randomUUID());
            set(shop, "name", "자동 복구 상품"); set(shop, "description", "이슈 22 테스트");
            set(shop, "priceKrw", 1000L); set(shop, "slotCode", AvatarSlot.HEAD);
            set(shop, "assetKey", "startup-test"); set(shop, "status", ShopItemStatus.ACTIVE);
            set(shop, "createdAt", now); set(shop, "updatedAt", now);
            em.persist(shop);
            var purchase = Purchase.ready(UUID.randomUUID().toString(), user, shop, now);
            purchase.markPaid(now); em.persist(purchase);
            var item = OwnedItem.grant(purchase, now); em.persist(item);
            var refund = RefundRequest.create(purchase, user, "자동 복구", now); em.persist(refund);
            em.flush();
            return new Fixture(user.getId(), shop.getId(), purchase.getId(), item.getId(), refund.getId());
        });
        var command = approval.prepare(fixture.userId(), fixture.refundId(), "승인", UUID.randomUUID().toString()).command();
        results.apply(command, new RefundGateway.CancellationResult(RefundGateway.Outcome.UNKNOWN,
                command.paymentId(), "startup-cancel-" + fixture.refundId(), 1000L, null, 200, "UNKNOWN"), "startup");
        when(lookup.getPayment(command.paymentId())).thenAnswer(call -> new PaymentSnapshot(
                command.paymentId(), "refund-startup-test", "approval", PaymentSnapshot.Status.CANCELLED,
                1000, "KRW", "CARD", null, "LIVE", Instant.now().minusSeconds(900),
                List.of(new PaymentSnapshot.CancellationSnapshot("startup-cancel-" + fixture.refundId(),
                        "SUCCEEDED", 1000, Instant.now().minusSeconds(600), Instant.now()))));
        jdbc.update("UPDATE refund_requests SET cancel_claimed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(600)), fixture.refundId());
        nextSlot = Instant.ofEpochSecond(Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300 + 300);
        System.out.println("REFUND_AUTO_CRON_TARGET=" + nextSlot);

        // 메서드를 수동 호출하거나 주기를 바꾸지 않고 실제 UTC 경계를 기다립니다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(315);
        while (System.nanoTime() < deadline) {
            String status = jobStatus(nextSlot);
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(jobStatus(nextSlot)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_requests WHERE id = ?", String.class, fixture.refundId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM owned_items WHERE id = ?", String.class, fixture.itemId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(result_summary, '$.completedCount') FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION' AND scheduled_for = ?",
                String.class, Timestamp.from(nextSlot))).isEqualTo("1");
        verify(lookup, times(1)).getPayment(command.paymentId());
        verifyNoInteractions(gateway);
    }

    @AfterEach
    void 정리() {
        if (fixture != null) {
            jdbc.update("DELETE FROM pg_api_attempts WHERE purchase_id = ?", fixture.purchaseId());
            jdbc.update("DELETE FROM payment_transactions WHERE purchase_id = ?", fixture.purchaseId());
            jdbc.update("DELETE FROM refund_requests WHERE purchase_id = ?", fixture.purchaseId());
            jdbc.update("DELETE FROM owned_items WHERE purchase_id = ?", fixture.purchaseId());
            jdbc.update("DELETE FROM purchases WHERE id = ?", fixture.purchaseId());
            jdbc.update("DELETE FROM shop_items WHERE id = ?", fixture.shopId());
            jdbc.update("DELETE FROM api_idempotency_records WHERE actor_user_id = ?", fixture.userId());
            jdbc.update("DELETE FROM users WHERE id = ?", fixture.userId());
        }
        if (state.startupSlot != null) deleteJob(state.startupSlot);
        if (nextSlot != null) deleteJob(nextSlot);
    }

    private void deleteJob(Instant slot) {
        jdbc.update("DELETE FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION' AND scheduled_for = ?", Timestamp.from(slot));
    }

    private String jobStatus(Instant slot) {
        var rows = jdbc.queryForList("SELECT status FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION' AND scheduled_for = ?",
                String.class, Timestamp.from(slot));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void set(Object target, String field, Object value) { ReflectionTestUtils.setField(target, field, value); }
    private record Fixture(Long userId, Long shopId, Long purchaseId, Long itemId, Long refundId) {}
    static class RunState { Instant startupSlot; }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean RunState runState() { return new RunState(); }
        @Bean ApplicationRunner guard(RefundRecoveryPreparationService preparation, JdbcTemplate jdbc, RunState state) {
            return args -> {
                // 기동 이벤트 전에 다른 테스트의 미확정 환불과 실행 기록을 보호합니다.
                assertThat(preparation.findScheduledCandidates()).isEmpty();
                Instant slot = Instant.ofEpochSecond(Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION' AND scheduled_for = ?",
                        Integer.class, Timestamp.from(slot))).isZero();
                state.startupSlot = slot;
            };
        }
    }

    public static class TestDatabaseOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            String url = context.getEnvironment().getProperty("spring.datasource.url", "");
            boolean valid = false;
            try { valid = url.startsWith("jdbc:mysql:") && "/shared_creature_game_test".equals(URI.create(url.substring(5)).getPath()); }
            catch (IllegalArgumentException ignored) { /* 접속 정보는 출력하지 않습니다. */ }
            if (!valid) throw new IllegalStateException("자동 실행 검증은 shared_creature_game_test DB만 허용합니다.");
        }
    }
}
