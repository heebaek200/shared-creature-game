package com.team.sharedcreature.payment.scheduler;

import com.team.sharedcreature.payment.application.PaymentWebhookReconciliationNormalizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 Webhook 복구의 유일한 Scheduler 트리거 클래스.
 * test 프로필과 명시적 기능 플래그 해제 상태에서는 자동 실행되지 않는다.
 * 이 클래스는 직접 트랜잭션을 시작하거나 결제 상태를 변경하지 않는다.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.scheduler", name = {"enabled", "payment-webhook-enabled"}, havingValue = "true")
public class PaymentWebhookReconciliationScheduler {

    private final PaymentWebhookReconciliationNormalizationService normalizer;

    public PaymentWebhookReconciliationScheduler(PaymentWebhookReconciliationNormalizationService normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * 서버 준비 완료 직후 현재 고착 이벤트를 한 번 점검한다.
     * 서버 중단 동안 지난 UTC 5분 구간 실행 이력은 소급 생성하지 않는다.
     * 같은 논리 구간의 Cron과 겹치면 기존 실행권으로 중복을 방지한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runSafely("APPLICATION_READY");
    }

    /**
     * UTC 매 5분 경계에 업무 복구를 호출한다.
     * 시간대와 관계없이 동일 구간의 scheduledFor가 생성되도록 한다.
     * 업무 서비스가 Clock을 기준으로 실제 5분 이상 고착된 항목을 선택한다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void onEveryFiveMinutes() {
        runSafely("FIVE_MINUTES");
    }

    /**
     * 업무 실패를 로그에 남기며 Spring Scheduler 스레드의 실행을 지속한다.
     * 실제 SchedulerExecution FAILED 상태 기록은 공통 Runner가 수행한다.
     * SUCCEEDED, SKIPPED, 실패를 동일한 결과 타입으로 관측한다.
     */
    private void runSafely(String trigger) {
        try {
            var result = normalizer.normalize();
            if (result.error() != null) {
                log.error("결제 Webhook 복구 오류. trigger={}, result={}", trigger, result);
            } else {
                log.info("결제 Webhook 복구 점검. trigger={}, result={}", trigger, result);
            }
        } catch (RuntimeException exception) {
            log.error("결제 Webhook 복구 트리거 오류. trigger={}", trigger, exception);
        }
    }

}
