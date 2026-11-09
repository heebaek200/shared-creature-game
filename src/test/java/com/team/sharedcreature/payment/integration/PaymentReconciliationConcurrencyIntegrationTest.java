package com.team.sharedcreature.payment.integration;

import com.team.sharedcreature.payment.application.PaymentGateway;
import com.team.sharedcreature.payment.application.PaymentReconciliationService;
import com.team.sharedcreature.payment.application.PaymentSnapshot;
import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 브라우저·Webhook 경합과 복구 조회의 DB 제약을 검증합니다. */
@SpringBootTest(properties = {
        "portone.store-id=store-test",
        "portone.channel-key=channel-test",
        "portone.api-secret=api-secret",
        "portone.webhook-secret=webhook-secret"
})
@ActiveProfiles("test")
@Import(PaymentReconciliationConcurrencyIntegrationTest.PaymentTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class PaymentReconciliationConcurrencyIntegrationTest {

    @Autowired private PaymentReconciliationService reconciliationService;
    @Autowired private PaymentWebhookEventRepository eventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private Long userId;
    private Long shopItemId;
    private Long purchaseId;
    private String paymentId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        createFixture();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM payment_webhook_events WHERE purchase_id = ?", purchaseId);
        jdbcTemplate.update("DELETE FROM owned_items WHERE purchase_id = ?", purchaseId);
        jdbcTemplate.update("DELETE FROM payment_transactions WHERE purchase_id = ?", purchaseId);
        jdbcTemplate.update("DELETE FROM purchases WHERE id = ?", purchaseId);
        jdbcTemplate.update("DELETE FROM shop_items WHERE id = ?", shopItemId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 브라우저와_Webhook이_동시에_정합화해도_승인거래와_아이템은_한번만_생성된다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<?> browser = executor.submit(() -> reconcileAfter(start));
        Future<?> webhook = executor.submit(() -> reconcileAfter(start));

        start.countDown();
        browser.get(10, TimeUnit.SECONDS);
        webhook.get(10, TimeUnit.SECONDS);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM purchases WHERE id = ?",
                String.class,
                purchaseId
        )).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE purchase_id = ? AND status = 'SUCCESS'",
                Integer.class,
                purchaseId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM owned_items WHERE purchase_id = ?",
                Integer.class,
                purchaseId
        )).isEqualTo(1);
    }

    @Test
    void 결제_복구_조회는_취소_Webhook을_선택하지_않는다() {
        Instant receivedAt = Instant.now().minusSeconds(600);
        insertWebhook("event-paid-" + UUID.randomUUID(), "Transaction.Paid", receivedAt);
        insertWebhook("event-cancel-" + UUID.randomUUID(), "Transaction.Cancelled", receivedAt);

        var events = eventRepository.findRecoverable(
                Set.of(
                        "Transaction.Ready",
                        "Transaction.Paid",
                        "Transaction.Failed",
                        "Transaction.PayPending"
                ),
                Instant.now().minusSeconds(300),
                PageRequest.of(0, 100)
        );

        assertThat(events).extracting(event -> event.getEventType())
                .contains("Transaction.Paid")
                .doesNotContain("Transaction.Cancelled");
    }

    private void reconcileAfter(CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
            reconciliationService.reconcileByPaymentId(paymentId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void createFixture() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = insertAndGetId("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES ('ACTIVE', 'USER', ?, ?, 0, ?, ?)
                """, now, now, now, now);
        shopItemId = insertAndGetId("""
                INSERT INTO shop_items (
                    code
                  , name
                  , description
                  , price_krw
                  , slot_code
                  , asset_key
                  , status
                  , created_at
                  , updated_at
                ) VALUES (?, '테스트 상품', '결제 동시성 테스트', 1000, 'HEAD', ?, 'ACTIVE', ?, ?)
                """, "PAY-" + suffix, "asset-" + suffix, now, now);
        paymentId = UUID.randomUUID().toString();
        purchaseId = insertAndGetId("""
                INSERT INTO purchases (
                    order_number
                  , user_id
                  , shop_item_id
                  , item_code_snapshot
                  , item_name_snapshot
                  , slot_code_snapshot
                  , price_krw_snapshot
                  , currency
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, ?, '테스트 상품', 'HEAD', 1000, 'KRW', 'READY', 0, ?, ?)
                """, paymentId, userId, shopItemId, "PAY-" + suffix, now, now);
    }

    private void insertWebhook(String eventId, String eventType, Instant receivedAt) {
        jdbcTemplate.update("""
                INSERT INTO payment_webhook_events (
                    provider
                  , provider_event_id
                  , provider_payment_id
                  , purchase_id
                  , event_type
                  , payload_hash
                  , payload_summary
                  , processing_status
                  , received_at
                  , processing_started_at
                  , attempt_count
                  , created_at
                  , updated_at
                ) VALUES ('PORTONE', ?, ?, ?, ?, ?, '{}', 'PENDING', ?, ?, 1, ?, ?)
                """,
                eventId,
                paymentId,
                purchaseId,
                eventType,
                "0".repeat(64),
                Timestamp.from(receivedAt),
                Timestamp.from(receivedAt),
                Timestamp.from(receivedAt),
                Timestamp.from(receivedAt)
        );
    }

    private Long insertAndGetId(String sql, Object... parameters) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < parameters.length; index++) {
                Object value = parameters[index];
                statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("테스트 데이터 ID를 생성하지 못했습니다.");
        }
        return key.longValue();
    }

    @TestConfiguration
    static class PaymentTestConfiguration {

        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return paymentId -> new PaymentSnapshot(
                    paymentId,
                    "store-test",
                    "tx-" + paymentId,
                    PaymentSnapshot.Status.PAID,
                    1000L,
                    "KRW",
                    "EASY_PAY",
                    "KAKAOPAY",
                    "TEST",
                    Instant.now(),
                    List.of()
            );
        }
    }
}
