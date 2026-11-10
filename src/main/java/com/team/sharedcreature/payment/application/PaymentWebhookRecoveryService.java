package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/** 공통 Scheduler가 호출하는 결제 Webhook 배치 복구 업무를 제공합니다. */
@Service
public class PaymentWebhookRecoveryService {

    private static final int BATCH_SIZE = 100;
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final PaymentWebhookEventRepository eventRepository;
    private final PaymentWebhookService webhookService;
    private final Clock clock;

    /** 복구 대상 조회와 단건 정합화 Service를 주입받습니다. */
    public PaymentWebhookRecoveryService(
            PaymentWebhookEventRepository eventRepository,
            PaymentWebhookService webhookService,
            Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.webhookService = webhookService;
        this.clock = clock;
    }

    /** 현재 시각에 복구 가능한 결제 이벤트를 최대 100건 처리하고 결과 건수를 반환합니다. */
    public PaymentWebhookRecoveryResult recoverStaleWebhooks() {
        var staleBefore = clock.instant().minus(STALE_AFTER);
        var events = eventRepository.findRecoverable(
                PaymentWebhookService.reconcilableTypes(),
                staleBefore,
                PageRequest.of(0, BATCH_SIZE)
        );
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (var event : events) {
            switch (webhookService.recover(event.getId(), staleBefore)) {
                case PROCESSED -> processed++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new PaymentWebhookRecoveryResult(events.size(), processed, skipped, failed);
    }

    /** Scheduler 실행 이력에 기록할 결제 복구 집계입니다. */
    public record PaymentWebhookRecoveryResult(
            int selectedCount,
            int processedCount,
            int skippedCount,
            int failedCount
    ) {
    }
}
