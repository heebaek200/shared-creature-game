package com.team.sharedcreature.payment.integration;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.domain.AvatarSlot;
import com.team.sharedcreature.avatar.domain.ShopItemStatus;
import com.team.sharedcreature.avatar.application.AvatarService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.payment.application.*;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import com.team.sharedcreature.payment.domain.WebhookProcessingStatus;
import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.PaymentTransactionStatus;
import com.team.sharedcreature.payment.domain.PaymentTransactionType;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.domain.RefundStatus;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 실제 MySQL에서 결과·회수·멱등 응답의 동시 Commit과 Rollback을 확인합니다. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.sql.init.mode=never",
        "app.scheduler.enabled=false",
        "portone.store-id=refund-test-store"
})
@ActiveProfiles("test")
@ContextConfiguration(initializers = RefundTransactionMySqlIntegrationTest.TestDatabaseOnly.class)
class RefundTransactionMySqlIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private PaymentWebhookEventRepository webhookRepository;
    @Autowired private PurchaseRepository purchaseRepository;
    @Autowired private PaymentTransactionRepository transactionRepository;
    @Autowired private PurchaseQueryService purchaseQueries;
    @Autowired private RefundRequestRepository refundRepository;
    @Autowired private RefundQueryService refundQueries;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundApprovalTransactionService approval;
    @Autowired private RefundResultTransactionService results;
    @Autowired private RefundService refunds;
    @Autowired private AvatarService avatars;
    @MockitoBean private RefundGateway gateway;
    @MockitoBean private RefundLookupGateway lookupGateway;
    @Autowired private RefundRecoveryPreparationService recoveryPreparation;
    @Autowired private RefundReconciliationService reconciliation;
    @Autowired private SchedulerJobRunner schedulerRunner;
    @Autowired private SchedulerExecutionLifecycleService schedulerLifecycle;
    private Instant schedulerBucket;
    private Fixture fixture;
    private Long enumWebhookId;
    private RefundApprovalTransactionService.CancellationCommand command;

    @BeforeEach
    void 준비() {
        // 테스트 전체에 트랜잭션을 걸지 않아 Service의 실제 Commit을 확인합니다.
        fixture = new TransactionTemplate(transactionManager).execute(status -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var owner = User.register("USER", now);
            var admin = User.register("SUPER_ADMIN", now);
            em.persist(owner);
            em.persist(admin);
            var shop = BeanUtils.instantiateClass(ShopItem.class);
            set(shop, "code", "refund-" + UUID.randomUUID());
            set(shop, "name", "환불 검증 상품");
            set(shop, "description", "이슈 22 테스트");
            set(shop, "priceKrw", 1000L);
            set(shop, "slotCode", AvatarSlot.HEAD);
            set(shop, "assetKey", "refund-test");
            set(shop, "status", ShopItemStatus.ACTIVE);
            set(shop, "createdAt", now);
            set(shop, "updatedAt", now);
            em.persist(shop);
            var purchase = Purchase.ready(UUID.randomUUID().toString(), owner, shop, now);
            purchase.markPaid(now);
            em.persist(purchase);
            var item = OwnedItem.grant(purchase, now);
            em.persist(item);
            var refund = RefundRequest.create(purchase, owner, "미사용 환불", now);
            em.persist(refund);
            em.flush();
            return new Fixture(owner.getId(), admin.getId(), shop.getId(), purchase.getId(),
                    item.getId(), refund.getId());
        });
    }

    @AfterEach
    void 정리() {
        // Enum 호환성 검증에서 생성한 단일 Webhook만 정리합니다.
        if (enumWebhookId != null) {
            jdbc.update("DELETE FROM payment_webhook_events WHERE id = ?", enumWebhookId);
        }
        if (fixture == null) {
            return;
        }
        // 이 테스트가 만든 행만 외래 키 의존 순서로 제거합니다.
        if (schedulerBucket != null) {
            jdbc.update("DELETE FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION' AND scheduled_for = ?",
                    Timestamp.from(schedulerBucket));
        }
        jdbc.update("DELETE FROM avatar_equipments WHERE user_id = ?", fixture.ownerId());
        jdbc.update("DELETE FROM pg_api_attempts WHERE purchase_id = ?", fixture.purchaseId());
        jdbc.update("DELETE FROM payment_transactions WHERE purchase_id = ?", fixture.purchaseId());
        jdbc.update("DELETE FROM refund_requests WHERE purchase_id = ?", fixture.purchaseId());
        jdbc.update("DELETE FROM owned_items WHERE purchase_id = ?", fixture.purchaseId());
        jdbc.update("DELETE FROM purchases WHERE id = ?", fixture.purchaseId());
        jdbc.update("DELETE FROM shop_items WHERE id = ?", fixture.shopId());
        jdbc.update("DELETE FROM api_idempotency_records WHERE actor_user_id IN (?, ?)", fixture.ownerId(), fixture.adminId());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.ownerId(), fixture.adminId());
    }

    @Test
    void Enum은_기존_DB문자열과_호환되고_구매슬롯Snapshot은_상품변경과_분리된다() {
        assertThat(jdbc.queryForObject("SELECT slot_code FROM shop_items WHERE id = ?",
                String.class, fixture.shopId())).isEqualTo("HEAD");
        assertThat(jdbc.queryForObject("SELECT status FROM shop_items WHERE id = ?",
                String.class, fixture.shopId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT slot_code_snapshot FROM purchases WHERE id = ?",
                String.class, fixture.purchaseId())).isEqualTo("HEAD");

        // 기존 문자열을 직접 저장해도 새 영속성 컨텍스트에서 Enum으로 읽혀야 합니다.
        jdbc.update("UPDATE shop_items SET slot_code = 'FACE', status = 'INACTIVE' WHERE id = ?",
                fixture.shopId());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var shop = em.find(ShopItem.class, fixture.shopId());
            var purchase = em.find(Purchase.class, fixture.purchaseId());
            assertThat(shop.getSlotCode()).isEqualTo(AvatarSlot.FACE);
            assertThat(shop.getStatus()).isEqualTo(ShopItemStatus.INACTIVE);
            assertThat(purchase.getSlotCodeSnapshot()).isEqualTo(AvatarSlot.HEAD);
        });
    }

    /** 기존 문자열을 읽고 실제 전환으로 저장한 구매 상태를 사용자 범위 안에서 조회합니다. */
    @ParameterizedTest
    @EnumSource(PurchaseStatus.class)
    void 구매상태는_기존문자열과_호환되고_Enum필터와_사용자범위를_유지한다(PurchaseStatus status) {
        // 이 테스트의 구매 행만 초기 문자열로 준비하여 기존 데이터 읽기를 검증합니다.
        jdbc.update("UPDATE purchases SET status = 'READY' WHERE id = ?", fixture.purchaseId());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var purchase = em.find(Purchase.class, fixture.purchaseId());
            assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.READY);
            switch (status) {
                case READY -> { }
                case PAID -> purchase.markPaid(now);
                case FAILED -> purchase.markFailed(now);
                case CANCELLED -> {
                    purchase.markPaid(now);
                    purchase.markCancelledAfterRefund(now);
                }
            }
        });

        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE id = ?",
                String.class, fixture.purchaseId())).isEqualTo(status.name());
        var mine = purchaseRepository.findByUserIdAndStatus(
                fixture.ownerId(), status, PageRequest.of(0, 20));
        assertThat(mine.getTotalElements()).isEqualTo(1);
        assertThat(mine.getContent().getFirst().getStatus()).isEqualTo(status);
        assertThat(purchaseRepository.findByUserIdAndStatus(
                fixture.adminId(), status, PageRequest.of(0, 20))).isEmpty();
    }

    /** 거래 Enum의 DB 문자열 호환성과 최근 취소 거래 조회·응답 계약을 검증합니다. */
    @ParameterizedTest
    @EnumSource(PaymentTransactionStatus.class)
    void 거래는_문자열로_저장되고_승인과_취소를_구분하여_조회한다(PaymentTransactionStatus status) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Long cancellationId = new TransactionTemplate(transactionManager).execute(tx -> {
            var purchase = em.find(Purchase.class, fixture.purchaseId());
            var cancellation = PaymentTransaction.cancellationRequested(purchase, now);
            if (status != PaymentTransactionStatus.REQUESTED) {
                var processedAt = status == PaymentTransactionStatus.UNKNOWN ? null : now;
                cancellation.applyCancellationResult(status, "cancel-" + fixture.refundId(), processedAt, "PG", now);
            }
            em.persist(cancellation);
            // 더 최신 승인 거래가 있어도 취소 유형 조회에서는 제외되어야 합니다.
            em.persist(PaymentTransaction.approved(purchase, "approval-" + fixture.purchaseId(),
                    purchase.getPriceKrwSnapshot(), now, now.plusSeconds(1)));
            em.flush();
            return cancellation.getId();
        });

        assertThat(jdbc.queryForObject("SELECT transaction_type FROM payment_transactions WHERE id = ?",
                String.class, cancellationId)).isEqualTo("CANCELLATION");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id = ?",
                String.class, cancellationId)).isEqualTo(status.name());
        var cancellation = transactionRepository.findFirstByPurchaseIdAndTransactionTypeOrderByCreatedAtDescIdDesc(
                fixture.purchaseId(), PaymentTransactionType.CANCELLATION).orElseThrow();
        assertThat(cancellation.getId()).isEqualTo(cancellationId);
        assertThat(cancellation.getStatus()).isEqualTo(status);
        assertThat(refundQueries.getAdminRefund(fixture.refundId()).latestCancellation().status())
                .isEqualTo(status.name());
        assertThat(purchaseQueries.get(fixture.ownerId(), fixture.purchaseId()).transactions())
                .anySatisfy(transaction -> {
                    assertThat(transaction.transactionType()).isEqualTo("CANCELLATION");
                    assertThat(transaction.status()).isEqualTo(status.name());
                });

        // 기존 문자열로 저장된 거래도 새 조회에서 Enum으로 읽힙니다.
        jdbc.update("UPDATE payment_transactions SET status = 'UNKNOWN' WHERE id = ?", cancellationId);
        assertThat(transactionRepository.findById(cancellationId).orElseThrow().getStatus())
                .isEqualTo(PaymentTransactionStatus.UNKNOWN);
    }

    /** Webhook 상태 문자열·Enum 읽기와 JPQL 복구 조건의 일치를 실제 DB로 검증합니다. */
    @ParameterizedTest
    @EnumSource(WebhookProcessingStatus.class)
    void Webhook상태는_기존문자열과_호환되고_복구대상조건을_유지한다(WebhookProcessingStatus status) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant startedAt = now.minusSeconds(600);
        enumWebhookId = new TransactionTemplate(transactionManager).execute(tx -> {
            var event = PaymentWebhookEvent.received("enum-62-" + UUID.randomUUID(), null, null,
                    "Transaction.Paid", "a".repeat(64), "{}", startedAt);
            switch (status) {
                case PENDING -> { }
                case FAILED -> event.fail("TEST_FAILURE", startedAt);
                case PROCESSED -> event.complete(null, null, startedAt);
                case IGNORED -> event.ignore(startedAt);
            }
            em.persist(event);
            em.flush();
            return event.getId();
        });

        assertThat(jdbc.queryForObject("SELECT processing_status FROM payment_webhook_events WHERE id = ?",
                String.class, enumWebhookId)).isEqualTo(status.name());
        assertThat(webhookRepository.findById(enumWebhookId).orElseThrow().getProcessingStatus()).isEqualTo(status);
        var candidates = webhookRepository.findRecoverable(List.of("Transaction.Paid"),
                now.minusSeconds(300), PageRequest.of(0, 1000));
        if (status == WebhookProcessingStatus.PENDING || status == WebhookProcessingStatus.FAILED) {
            assertThat(candidates).extracting(PaymentWebhookEvent::getId).contains(enumWebhookId);
        } else {
            assertThat(candidates).extracting(PaymentWebhookEvent::getId).doesNotContain(enumWebhookId);
        }

        // 기존 DB 문자열도 새 조회에서 동일한 Enum으로 읽혀야 합니다.
        jdbc.update("UPDATE payment_webhook_events SET processing_status = 'IGNORED' WHERE id = ?", enumWebhookId);
        assertThat(webhookRepository.findById(enumWebhookId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.IGNORED);
    }

    /** 모든 환불 상태의 문자열 저장·Enum 조회와 복구 대상 JPQL을 실제 MySQL로 검증합니다. */
    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void 환불상태는_문자열로_저장하고_목록과_복구쿼리에_Enum으로_적용한다(RefundStatus status) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant claimedAt = now.minusSeconds(600);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var refund = em.find(RefundRequest.class, fixture.refundId());
            var admin = em.find(User.class, fixture.adminId());
            if (status == RefundStatus.REJECTED) {
                refund.reject(admin, "거절", claimedAt);
            } else if (status != RefundStatus.REQUESTED) {
                refund.claimCancellation(admin, "승인", claimedAt);
                switch (status) {
                    case CANCEL_UNKNOWN -> refund.markCancellationUnknown(claimedAt);
                    case COMPLETED -> refund.completeCancellation(claimedAt, claimedAt);
                    case FAILED -> refund.failCancellation(claimedAt);
                    default -> { }
                }
            }
        });

        assertThat(jdbc.queryForObject("SELECT status FROM refund_requests WHERE id = ?",
                String.class, fixture.refundId())).isEqualTo(status.name());
        var mine = refundQueries.listMyRefunds(fixture.ownerId(), 0, 20, status.name());
        assertThat(mine.getTotalElements()).isEqualTo(1);
        assertThat(mine.getContent().getFirst().status()).isEqualTo(status.name());
        assertThat(refundQueries.listMyRefunds(fixture.adminId(), 0, 20, status.name())).isEmpty();

        // 목록과 전체 건수 계산에 같은 Enum 상태 조건이 적용되는지 확인합니다.
        var all = refundRepository.findAdminRefunds(status, PageRequest.of(0, 100));
        assertThat(all.getContent()).allSatisfy(refund -> assertThat(refund.getStatus()).isEqualTo(status));
        assertThat(all.getTotalElements()).isGreaterThanOrEqualTo(1);
        var stale = refundRepository.findStaleCancellationIds(now.minusSeconds(300), PageRequest.of(0, 100));
        if (status == RefundStatus.CANCEL_PENDING || status == RefundStatus.CANCEL_UNKNOWN) {
            assertThat(stale).contains(fixture.refundId());
        } else {
            assertThat(stale).doesNotContain(fixture.refundId());
        }
    }

    @Test
    void 성공이면_환불_구매_회수_멱등응답을_함께_Commit하고_재반영하지_않는다() {
        prepareApproval();
        var response = results.apply(command, pgResult(RefundGateway.Outcome.SUCCESS), "first");
        assertThat(response.httpStatus()).isEqualTo(200);
        assertState("COMPLETED", "CANCELLED", "REVOKED", "COMPLETED");
        Timestamp revokedAt = jdbc.queryForObject("SELECT revoked_at FROM owned_items WHERE id = ?",
                Timestamp.class, fixture.itemId());
        var replay = results.apply(command, pgResult(RefundGateway.Outcome.UNKNOWN), "second");
        assertThat(replay.data().status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM owned_items WHERE id = ?",
                Timestamp.class, fixture.itemId())).isEqualTo(revokedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE purchase_id = ? AND transaction_type = 'CANCELLATION'",
                Integer.class, fixture.purchaseId())).isEqualTo(1);
    }

    @Test
    void 결과불명은_409와_UNKNOWN을_Commit하고_아이템을_유지한다() {
        prepareApproval();
        var response = results.apply(command, pgResult(RefundGateway.Outcome.UNKNOWN), "first");
        assertThat(response.httpStatus()).isEqualTo(409);
        assertThat(response.error().code()).isEqualTo("PG_CANCEL_RESULT_UNKNOWN");
        assertState("CANCEL_UNKNOWN", "PAID", "OWNED_UNUSED", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT http_status FROM api_idempotency_records WHERE id = ?",
                Integer.class, command.idempotencyRecordId())).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM owned_items WHERE id = ?",
                Timestamp.class, fixture.itemId())).isNull();
    }

    @Test
    void 회수_검증이_실패하면_앞선_환불_구매_거래변경도_함께_Rollback한다() {
        prepareApproval();
        // 데이터 불일치를 주입하여 결과 저장 트랜잭션의 중간 실패를 유도합니다.
        jdbc.update("UPDATE owned_items SET status = 'OWNED_USED', used_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), fixture.itemId());
        assertThatThrownBy(() -> results.apply(command, pgResult(RefundGateway.Outcome.SUCCESS), "trace"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.REFUND_NOT_ALLOWED_USED_ITEM));
        assertState("CANCEL_PENDING", "PAID", "OWNED_USED", "PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE purchase_id = ?",
                Integer.class, fixture.purchaseId())).isZero();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM pg_api_attempts WHERE id = ?",
                Timestamp.class, command.pgApiAttemptId())).isNull();
    }

    @RepeatedTest(3)
    void 서로다른_멱등키의_동시승인도_PG취소는_한번만_실행한다() throws Exception {
        stubSuccessfulCancellation();
        var outcomes = race(this::approve, this::approve);
        assertThat(outcomes.stream().filter(value -> value instanceof RefundApprovalTransactionService.CommandResponse).count()).isEqualTo(1);
        assertThat(outcomes).contains(ErrorCode.CONCURRENT_MODIFICATION);
        verify(gateway, times(1)).cancelFully(anyString(), anyString(), anyString());
        assertThat(status("refund_requests", fixture.refundId())).isEqualTo("COMPLETED");
        assertThat(status("owned_items", fixture.itemId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_api_attempts WHERE purchase_id = ? AND operation = 'CANCELLATION'",
                Integer.class, fixture.purchaseId())).isEqualTo(1);
    }

    @RepeatedTest(3)
    void 동시_승인과_거절은_하나만_확정한다() throws Exception {
        stubSuccessfulCancellation();
        var outcomes = race(this::approve,
                () -> refunds.rejectRefund(fixture.adminId(), fixture.refundId(), "검증 거절"));
        assertThat(outcomes.stream().filter(value -> value instanceof ErrorCode).count()).isEqualTo(1);
        assertThat(outcomes).contains(ErrorCode.CONCURRENT_MODIFICATION);
        String refundStatus = status("refund_requests", fixture.refundId());
        assertThat(refundStatus).isIn("COMPLETED", "REJECTED");
        boolean completed = "COMPLETED".equals(refundStatus);
        verify(gateway, times(completed ? 1 : 0)).cancelFully(anyString(), anyString(), anyString());
        assertThat(status("purchases", fixture.purchaseId())).isEqualTo(completed ? "CANCELLED" : "PAID");
        assertThat(status("owned_items", fixture.itemId())).isEqualTo(completed ? "REVOKED" : "OWNED_UNUSED");
    }

    @RepeatedTest(3)
    void 환불요청과_최초장착이_경합해도_사용과_환불이_동시에_성립하지_않는다() throws Exception {
        jdbc.update("DELETE FROM refund_requests WHERE id = ?", fixture.refundId());
        var outcomes = race(
                () -> refunds.createRefundRequest(fixture.ownerId(), fixture.purchaseId(), "동시 환불", UUID.randomUUID().toString()),
                () -> avatars.equipAvatarItem(fixture.ownerId(), "HEAD", fixture.itemId(), true));
        assertThat(outcomes.stream().filter(value -> value instanceof ErrorCode).count()).isEqualTo(1);
        Timestamp usedAt = jdbc.queryForObject("SELECT used_at FROM owned_items WHERE id = ?", Timestamp.class, fixture.itemId());
        int refundCount = jdbc.queryForObject("SELECT COUNT(*) FROM refund_requests WHERE purchase_id = ? AND status = 'REQUESTED'",
                Integer.class, fixture.purchaseId());
        int equipmentCount = jdbc.queryForObject("SELECT COUNT(*) FROM avatar_equipments WHERE owned_item_id = ?",
                Integer.class, fixture.itemId());
        if (usedAt == null) {
            assertThat(refundCount).isEqualTo(1);
            assertThat(equipmentCount).isZero();
            assertThat(outcomes).contains(ErrorCode.CONCURRENT_MODIFICATION);
        } else {
            assertThat(refundCount).isZero();
            assertThat(equipmentCount).isEqualTo(1);
            assertThat(outcomes).contains(ErrorCode.REFUND_NOT_ALLOWED_USED_ITEM);
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void 실제_복구와_Scheduler_실행이력을_함께_성공으로_확정한다() {
        prepareStaleUnknownRefund();
        when(lookupGateway.getPayment(command.paymentId())).thenReturn(new PaymentSnapshot(
                command.paymentId(), "refund-test-store", "approval-test", PaymentSnapshot.Status.CANCELLED,
                1000, "KRW", "CARD", null, "LIVE", Instant.now().minusSeconds(900),
                List.of(new PaymentSnapshot.CancellationSnapshot("refund-test-" + fixture.refundId(),
                        "SUCCEEDED", 1000, Instant.now().minusSeconds(600), Instant.now()))));
        var result = normalizer().normalize();
        assertThat(result.error()).isNull();
        assertThat(result.execution()).isEqualTo(SchedulerJobRunner.ExecutionResult.SUCCEEDED);
        assertThat(result.summary()).isEqualTo(new RefundRecoveryWork.RecoverySummary(1, 1, 0, 0, 0, 0, 0));
        assertThat(status("refund_requests", fixture.refundId())).isEqualTo("COMPLETED");
        assertThat(status("owned_items", fixture.itemId())).isEqualTo("REVOKED");
        assertThat(schedulerColumn("status")).isEqualTo("SUCCEEDED");
        assertThat(schedulerColumn("result_summary")).contains("completedCount");
        // 실제 전체 취소 API를 다시 호출하지 않고 최신 조회만 사용합니다.
        verifyNoInteractions(gateway);
        verify(lookupGateway).getPayment(command.paymentId());
    }

    @Test
    void PG조회장애는_환불을_유지하고_오류집계를_반환하며_Scheduler를_실패로_기록한다() {
        prepareStaleUnknownRefund();
        when(lookupGateway.getPayment(command.paymentId())).thenThrow(new PaymentGatewayException(
                PaymentGatewayException.Kind.UNAVAILABLE, "조회 Timeout 대역", null));
        var result = normalizer().normalize();
        assertThat(result.error()).isNotNull();
        assertThat(result.summary()).isEqualTo(new RefundRecoveryWork.RecoverySummary(1, 0, 0, 0, 0, 0, 1));
        assertThat(status("refund_requests", fixture.refundId())).isEqualTo("CANCEL_UNKNOWN");
        assertThat(status("owned_items", fixture.itemId())).isEqualTo("OWNED_UNUSED");
        assertThat(schedulerColumn("status")).isEqualTo("FAILED");
        verifyNoInteractions(gateway);
    }

    @Test
    void 실패회차는_오류정보를_기록하고_성공전용_집계JSON은_저장하지_않는다() {
        prepareStaleUnknownRefund();
        when(lookupGateway.getPayment(command.paymentId())).thenThrow(new PaymentGatewayException(
                PaymentGatewayException.Kind.UNAVAILABLE, "조회 Timeout 대역", null));
        var result = normalizer().normalize();
        assertThat(result.summary().errorCount()).isEqualTo(1);
        assertThat(schedulerColumn("status")).isEqualTo("FAILED");
        assertThat(schedulerColumn("attempt_count")).isEqualTo("1");
        assertThat(schedulerColumn("error_code")).isEqualTo("JOB_EXECUTION_FAILED");
        assertThat(schedulerColumn("error_detail")).contains("IllegalStateException");
        // #24 확인 정책: 집계 JSON은 성공 실행에서만 기록합니다.
        assertThat(schedulerColumn("result_summary")).isNull();
        verifyNoInteractions(gateway);
    }

    @RepeatedTest(3)
    void 다른_실행구간의_복구가_겹쳐도_단건_조회는_하나만_선점한다() throws Exception {
        prepareStaleUnknownRefund();
        var outcomes = race(
                () -> recoveryPreparation.prepareScheduled(fixture.refundId()),
                () -> recoveryPreparation.prepareScheduled(fixture.refundId()));
        long claimed = outcomes.stream()
                .map(value -> (java.util.Optional<?>) value)
                .filter(java.util.Optional::isPresent)
                .count();
        assertThat(claimed).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_api_attempts WHERE refund_request_id = ? AND operation = 'CANCELLATION_LOOKUP'",
                Integer.class, fixture.refundId())).isEqualTo(1);
        verifyNoInteractions(gateway, lookupGateway);
    }

    @Test
    void 중단된_조회는_재선점하고_이전응답과_중복응답은_회수에_반영하지_않는다() {
        prepareStaleUnknownRefund();
        var oldCommand = recoveryPreparation.prepareScheduled(fixture.refundId()).orElseThrow();
        Timestamp stale = Timestamp.from(Instant.now().minusSeconds(600));
        // 선점 Commit 뒤 서버가 중단되어 조회 완료를 기록하지 못한 상황입니다.
        jdbc.update("UPDATE refund_requests SET cancel_claimed_at = ? WHERE id = ?", stale, fixture.refundId());
        jdbc.update("UPDATE pg_api_attempts SET started_at = ? WHERE id = ?", stale, oldCommand.lookupAttemptId());
        var newCommand = recoveryPreparation.prepareScheduled(fixture.refundId()).orElseThrow();
        var snapshot = successfulLookup();
        assertThat(results.applyRecovery(oldCommand, snapshot)).isEmpty();
        assertThat(status("refund_requests", fixture.refundId())).isEqualTo("CANCEL_UNKNOWN");
        assertThat(status("owned_items", fixture.itemId())).isEqualTo("OWNED_UNUSED");
        assertThat(results.applyRecovery(newCommand, snapshot)).contains(RefundGateway.Outcome.SUCCESS);
        Timestamp revokedAt = jdbc.queryForObject("SELECT revoked_at FROM owned_items WHERE id = ?", Timestamp.class, fixture.itemId());
        assertThat(results.applyRecovery(newCommand, snapshot)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM owned_items WHERE id = ?", Timestamp.class, fixture.itemId())).isEqualTo(revokedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE purchase_id = ? AND transaction_type = 'CANCELLATION'",
                Integer.class, fixture.purchaseId())).isEqualTo(1);
    }

    @Test
    void 승인준비후_중단된_요청은_복구후_같은멱등키로_완료응답을_받는다() {
        String key = UUID.randomUUID().toString();
        command = approval.prepare(fixture.adminId(), fixture.refundId(), "중단 검증", key).command();
        Timestamp stale = Timestamp.from(Instant.now().minusSeconds(600));
        // PG 취소는 성공했지만 서버가 결과 저장 전에 중단된 상황을 재현합니다.
        jdbc.update("UPDATE refund_requests SET cancel_claimed_at = ? WHERE id = ?", stale, fixture.refundId());
        jdbc.update("UPDATE pg_api_attempts SET started_at = ? WHERE id = ?", stale, command.pgApiAttemptId());
        assertThat(recoveryPreparation.findScheduledCandidates()).containsExactly(fixture.refundId());
        when(lookupGateway.getPayment(command.paymentId())).thenReturn(successfulLookup());
        var summary = reconciliation.recoverStaleRefunds(() -> {});
        assertThat(summary.completedCount()).isEqualTo(1);
        assertThat(status("refund_requests", fixture.refundId())).isEqualTo("COMPLETED");
        assertThat(status("owned_items", fixture.itemId())).isEqualTo("REVOKED");
        // 업무만 복구되고 멱등 키가 영구 PROCESSING으로 남으면 재요청이 막힙니다.
        var response = refunds.approveRefund(fixture.adminId(), fixture.refundId(), "중단 검증", key, "retry");
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.data().status()).isEqualTo("COMPLETED");
        verifyNoInteractions(gateway);
    }

    private PaymentSnapshot successfulLookup() {
        return new PaymentSnapshot(command.paymentId(), "refund-test-store", "approval-test",
                PaymentSnapshot.Status.CANCELLED, 1000, "KRW", "CARD", null, "LIVE",
                Instant.now().minusSeconds(900), List.of(new PaymentSnapshot.CancellationSnapshot(
                "refund-test-" + fixture.refundId(), "SUCCEEDED", 1000,
                Instant.now().minusSeconds(600), Instant.now())));
    }

    private void prepareStaleUnknownRefund() {
        prepareApproval();
        results.apply(command, pgResult(RefundGateway.Outcome.UNKNOWN), "unknown-before-recovery");
        Timestamp stale = Timestamp.from(Instant.now().minusSeconds(600));
        jdbc.update("UPDATE refund_requests SET cancel_claimed_at = ?, updated_at = ? WHERE id = ?",
                stale, stale, fixture.refundId());
        // 이 테스트 외의 미확정 환불을 변경하지 않도록 후보를 먼저 검사합니다.
        assertThat(recoveryPreparation.findScheduledCandidates()).containsExactly(fixture.refundId());
    }

    private RefundReconciliationNormalizationService normalizer() {
        Timestamp latest = jdbc.queryForObject(
                "SELECT MAX(scheduled_for) FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION'",
                Timestamp.class);
        long next = Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300;
        if (latest != null) {
            next = Math.max(next, latest.toInstant().getEpochSecond() + 300);
        }
        schedulerBucket = Instant.ofEpochSecond(next);
        // 자동 트리거는 비활성 상태로 두고 실제 #24 실행 계층을 직접 연결합니다.
        return new RefundReconciliationNormalizationService(schedulerRunner, schedulerLifecycle,
                reconciliation, Clock.fixed(schedulerBucket, ZoneOffset.UTC));
    }

    private String schedulerColumn(String column) {
        // 조회 컬럼은 테스트 코드의 고정된 이름만 사용합니다.
        return jdbc.queryForObject("SELECT " + column + " FROM scheduler_executions WHERE job_type = 'REFUND_RECONCILIATION' AND scheduled_for = ?",
                String.class, Timestamp.from(schedulerBucket));
    }

    private Object approve() {
        return refunds.approveRefund(fixture.adminId(), fixture.refundId(), "검증 승인", UUID.randomUUID().toString(), "race");
    }

    private void stubSuccessfulCancellation() {
        // 실제 PortOne에는 연결하지 않고, 취소 호출 횟수와 DB 처리만 확인합니다.
        when(gateway.cancelFully(anyString(), anyString(), anyString())).thenAnswer(call ->
                new RefundGateway.CancellationResult(RefundGateway.Outcome.SUCCESS, call.getArgument(0),
                        "race-cancel-" + fixture.refundId(), 1000L,
                        Instant.now().truncatedTo(ChronoUnit.MICROS), 200, "SUCCEEDED"));
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var left = executor.submit(() -> runAfterStart(first, ready, start));
            var right = executor.submit(() -> runAfterStart(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Object runAfterStart(Callable<?> work, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("병렬 테스트 시작 대기 시간 초과");
        }
        try {
            return work.call();
        } catch (BusinessException exception) {
            return exception.errorCode();
        }
    }

    private void prepareApproval() {
        command = approval.prepare(fixture.adminId(), fixture.refundId(), "검증 승인",
                UUID.randomUUID().toString()).command();
    }

    private String status(String table, Long id) {
        // 테이블명은 위 테스트 코드의 고정된 값만 사용합니다.
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private void assertState(String refund, String purchase, String item, String idempotency) {
        assertThat(jdbc.queryForObject("SELECT status FROM refund_requests WHERE id = ?", String.class, fixture.refundId())).isEqualTo(refund);
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE id = ?", String.class, fixture.purchaseId())).isEqualTo(purchase);
        assertThat(jdbc.queryForObject("SELECT status FROM owned_items WHERE id = ?", String.class, fixture.itemId())).isEqualTo(item);
        assertThat(jdbc.queryForObject("SELECT status FROM api_idempotency_records WHERE id = ?", String.class, command.idempotencyRecordId())).isEqualTo(idempotency);
    }

    private RefundGateway.CancellationResult pgResult(RefundGateway.Outcome outcome) {
        return new RefundGateway.CancellationResult(outcome, command.paymentId(),
                "refund-test-" + fixture.refundId(), 1000L,
                outcome == RefundGateway.Outcome.SUCCESS ? Instant.now().truncatedTo(ChronoUnit.MICROS) : null,
                200, outcome.name());
    }

    private static void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }

    private record Fixture(Long ownerId, Long adminId, Long shopId, Long purchaseId, Long itemId, Long refundId) {}

    /** 건너뛰기 대신 설정 오류로 실패시키며 개발 DB 연결을 차단합니다. */
    public static class TestDatabaseOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String url = context.getEnvironment().getProperty("spring.datasource.url", "");
            boolean valid = false;
            try {
                valid = url.startsWith("jdbc:mysql:")
                        && "/shared_creature_game_test".equals(URI.create(url.substring(5)).getPath());
            } catch (IllegalArgumentException ignored) {
                // 접속 정보는 오류 메시지에 노출하지 않습니다.
            }
            if (!valid) {
                throw new IllegalStateException("환불 DB 테스트는 shared_creature_game_test DB가 필요합니다.");
            }
        }
    }
}
