package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

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
    void 미등록_주문의_결제_Webhook은_식별자를_보존하고_무시한다() {
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
        assertThat(captor.getValue().getProcessingStatus()).isEqualTo("IGNORED");
        verifyNoInteractions(reconciliationService);
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionsImmediately() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
    }
}
