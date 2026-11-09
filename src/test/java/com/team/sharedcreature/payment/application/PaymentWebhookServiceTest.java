package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    @Mock private PortOneWebhookVerifier verifier;
    @Mock private PaymentWebhookEventRepository eventRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private PaymentReconciliationService reconciliationService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TransactionStatus transactionStatus;

    private PaymentWebhookService service;

    @BeforeEach
    void setUp() {
        service = new PaymentWebhookService(
                verifier,
                new ObjectMapper(),
                eventRepository,
                purchaseRepository,
                transactionRepository,
                reconciliationService,
                transactionTemplate,
                Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void 취소_Webhook은_식별자를_먼저_보존하고_결제나_환불을_확정하지_않는다() {
        String body = """
                {"type":"Transaction.Cancelled","timestamp":"2026-11-09T08:00:00Z","data":{
                  "storeId":"store-test","paymentId":"payment-21","transactionId":"tx-1","cancellationId":"cancel-1"
                }}
                """;
        when(eventRepository.findByProviderAndProviderEventId("PORTONE", "event-1"))
                .thenReturn(Optional.empty());
        when(purchaseRepository.findByOrderNumber("payment-21")).thenReturn(Optional.empty());
        when(eventRepository.save(any(PaymentWebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        executeTransactionsImmediately();

        service.receive(body, "event-1", "signature", "100");

        ArgumentCaptor<PaymentWebhookEvent> captor = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(eventRepository).save(captor.capture());
        PaymentWebhookEvent event = captor.getValue();
        assertThat(event.getProviderPaymentId()).isEqualTo("payment-21");
        assertThat(event.getEventType()).isEqualTo("Transaction.Cancelled");
        assertThat(event.getPayloadSummary()).contains("cancel-1");
        assertThat(event.getPayloadSummary()).doesNotContain("signature");
        assertThat(event.getProcessingStatus()).isEqualTo("PENDING");
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void 같은_공급자_이벤트를_재수신하면_업무를_반복하지_않는다() {
        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-11-09T08:00:00Z","data":{
                  "storeId":"store-test","paymentId":"payment-21","transactionId":"tx-1"
                }}
                """;
        when(eventRepository.findByProviderAndProviderEventId("PORTONE", "event-1"))
                .thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));
        executeTransactionsImmediately();

        service.receive(body, "event-1", "signature", "100");

        verify(eventRepository, never()).save(any());
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void 등록된_결제_Webhook은_최신_조회_후_구매와_거래를_연결한다() {
        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-11-09T08:00:00Z","data":{
                  "storeId":"store-test","paymentId":"payment-21","transactionId":"tx-1"
                }}
                """;
        Purchase purchase = mock(Purchase.class);
        PaymentTransaction transaction = mock(PaymentTransaction.class);
        AtomicReference<PaymentWebhookEvent> saved = new AtomicReference<>();
        when(eventRepository.findByProviderAndProviderEventId("PORTONE", "event-1"))
                .thenReturn(Optional.empty());
        when(purchaseRepository.findByOrderNumber("payment-21")).thenReturn(Optional.of(purchase));
        when(eventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(invocation -> {
            PaymentWebhookEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "id", 1L);
            saved.set(event);
            return event;
        });
        when(reconciliationService.reconcileByPaymentId("payment-21"))
                .thenReturn(new PaymentReconciliationService.ReconciliationResult(10L, 20L, "PAID"));
        when(eventRepository.findByIdForUpdate(1L)).thenAnswer(invocation -> Optional.of(saved.get()));
        when(purchaseRepository.findById(10L)).thenReturn(Optional.of(purchase));
        when(transactionRepository.findById(20L)).thenReturn(Optional.of(transaction));
        executeTransactionsImmediately();

        service.receive(body, "event-1", "signature", "100");

        assertThat(saved.get().getProcessingStatus()).isEqualTo("PROCESSED");
        assertThat(saved.get().getPurchase()).isSameAs(purchase);
        assertThat(saved.get().getPaymentTransaction()).isSameAs(transaction);
    }

    @Test
    void 미등록_주문의_결제_Webhook은_식별자를_보존하고_복구대상으로_유지한다() {
        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-11-09T08:00:00Z","data":{
                  "storeId":"store-test","paymentId":"unknown-payment","transactionId":"tx-1"
                }}
                """;
        when(eventRepository.findByProviderAndProviderEventId("PORTONE", "event-1"))
                .thenReturn(Optional.empty());
        when(purchaseRepository.findByOrderNumber("unknown-payment")).thenReturn(Optional.empty());
        when(eventRepository.save(any(PaymentWebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        executeTransactionsImmediately();

        service.receive(body, "event-1", "signature", "100");

        ArgumentCaptor<PaymentWebhookEvent> captor = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getProviderPaymentId()).isEqualTo("unknown-payment");
        assertThat(captor.getValue().getProcessingStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getFailureDetail()).isEqualTo("PURCHASE_NOT_FOUND");
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void 조회_후_이미_종료된_이벤트는_잠금_후_다시_Pending으로_되돌리지_않는다() {
        PaymentWebhookEvent event = PaymentWebhookEvent.received(
                "event-1",
                "payment-21",
                null,
                "Transaction.Paid",
                "hash",
                "{}",
                Instant.parse("2026-11-09T07:50:00Z")
        );
        event.ignore(Instant.parse("2026-11-09T07:59:00Z"));
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        executeTransactionsImmediately();

        var outcome = service.recover(1L, Instant.parse("2026-11-09T07:55:00Z"));

        assertThat(outcome).isEqualTo(PaymentWebhookService.RecoveryOutcome.SKIPPED);
        assertThat(event.getProcessingStatus()).isEqualTo("IGNORED");
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void 미매칭_이벤트는_주문이_확인되면_구매와_거래를_연결해_복구한다() {
        PaymentWebhookEvent event = PaymentWebhookEvent.received(
                "event-1",
                "payment-21",
                null,
                "Transaction.Paid",
                "hash",
                "{}",
                Instant.parse("2026-11-09T07:50:00Z")
        );
        ReflectionTestUtils.setField(event, "id", 1L);
        event.fail("PURCHASE_NOT_FOUND", Instant.parse("2026-11-09T07:50:01Z"));
        Purchase purchase = mock(Purchase.class);
        PaymentTransaction transaction = mock(PaymentTransaction.class);
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        when(reconciliationService.reconcileByPaymentId("payment-21"))
                .thenReturn(new PaymentReconciliationService.ReconciliationResult(10L, 20L, "PAID"));
        when(purchaseRepository.findById(10L)).thenReturn(Optional.of(purchase));
        when(transactionRepository.findById(20L)).thenReturn(Optional.of(transaction));
        executeTransactionsImmediately();

        var outcome = service.recover(1L, Instant.parse("2026-11-09T07:55:00Z"));

        assertThat(outcome).isEqualTo(PaymentWebhookService.RecoveryOutcome.PROCESSED);
        assertThat(event.getProcessingStatus()).isEqualTo("PROCESSED");
        assertThat(event.getPurchase()).isSameAs(purchase);
        assertThat(event.getPaymentTransaction()).isSameAs(transaction);
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionsImmediately() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }
}
