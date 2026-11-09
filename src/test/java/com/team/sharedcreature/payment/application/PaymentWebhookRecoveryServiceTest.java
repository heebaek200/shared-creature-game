package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");

    @Mock private PaymentWebhookEventRepository eventRepository;
    @Mock private PaymentWebhookService webhookService;

    @Test
    void 결제_이벤트만_조회하고_단건_결과를_집계한다() {
        PaymentWebhookEvent processed = event(1L);
        PaymentWebhookEvent skipped = event(2L);
        PaymentWebhookEvent failed = event(3L);
        when(eventRepository.findRecoverable(any(), eq(NOW.minusSeconds(300)), any(Pageable.class)))
                .thenReturn(List.of(processed, skipped, failed));
        when(webhookService.recover(1L, NOW.minusSeconds(300)))
                .thenReturn(PaymentWebhookService.RecoveryOutcome.PROCESSED);
        when(webhookService.recover(2L, NOW.minusSeconds(300)))
                .thenReturn(PaymentWebhookService.RecoveryOutcome.SKIPPED);
        when(webhookService.recover(3L, NOW.minusSeconds(300)))
                .thenReturn(PaymentWebhookService.RecoveryOutcome.FAILED);
        PaymentWebhookRecoveryService service = new PaymentWebhookRecoveryService(
                eventRepository,
                webhookService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var result = service.recoverStaleWebhooks();

        assertThat(result.selectedCount()).isEqualTo(3);
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        verify(eventRepository).findRecoverable(types.capture(), eq(NOW.minusSeconds(300)), any(Pageable.class));
        assertThat(types.getValue()).containsExactlyInAnyOrder(
                "Transaction.Ready",
                "Transaction.Paid",
                "Transaction.Failed",
                "Transaction.PayPending"
        );
        assertThat(types.getValue()).noneMatch(type -> type.contains("Cancel"));
    }

    private PaymentWebhookEvent event(Long id) {
        PaymentWebhookEvent event = mock(PaymentWebhookEvent.class);
        when(event.getId()).thenReturn(id);
        return event;
    }
}
