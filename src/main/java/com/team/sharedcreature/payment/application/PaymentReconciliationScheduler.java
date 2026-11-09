package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** 장기 PENDING·FAILED 결제 Webhook을 PortOne 최신 상태로 복구합니다. */
@Component
public class PaymentReconciliationScheduler {

    private static final int BATCH_SIZE = 100;
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final PaymentWebhookEventRepository eventRepository;
    private final PaymentWebhookService webhookService;
    private final Clock clock;

    /** 복구 대상 조회와 단건 정합화 Service를 주입받습니다. */
    public PaymentReconciliationScheduler(
            PaymentWebhookEventRepository eventRepository,
            PaymentWebhookService webhookService,
            Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.webhookService = webhookService;
        this.clock = clock;
    }

    /** 5분마다 현재 고착 상태만 최대 100건 복구합니다. */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void reconcileStaleWebhooks() {
        eventRepository.findRecoverable(clock.instant().minus(STALE_AFTER), PageRequest.of(0, BATCH_SIZE))
                .forEach(event -> webhookService.recover(event.getId()));
    }

    /** 서버 중단 중 누락된 Tick을 재생하지 않고 기동 시 현재 대상만 점검합니다. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcileStaleWebhooks();
    }
}
