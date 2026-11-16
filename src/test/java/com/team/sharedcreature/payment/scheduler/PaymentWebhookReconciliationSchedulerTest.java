package com.team.sharedcreature.payment.scheduler;

import com.team.sharedcreature.payment.application.PaymentWebhookReconciliationNormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 결제 Webhook 복구의 Spring 트리거와 활성화 조건을 검사합니다.
 * 실제 DB나 PortOne 요청 없이 정상화 서비스 위임 경로를 확인합니다.
 * Scheduler 이외의 구성에 업무 @Scheduled가 없어야 중복 실행을 피할 수 있습니다.
 */
class PaymentWebhookReconciliationSchedulerTest {
    private final PaymentWebhookReconciliationNormalizationService normalizer =
            mock(PaymentWebhookReconciliationNormalizationService.class);
    private final PaymentWebhookReconciliationScheduler scheduler =
            new PaymentWebhookReconciliationScheduler(normalizer);

    /**
     * 서버 기동과 UTC 5분 점검이 모두 동일한 정상화 메서드를 호출합니다.
     * 두 트리거는 서로 다른 업무 서비스를 만들지 않습니다.
     * 실행권과 실패 이력은 정상화 계층에서 통합 처리됩니다.
     */
    @Test
    void startupAndCronShareNormalizer() {
        when(normalizer.normalize()).thenReturn(result(null));
        scheduler.onApplicationReady();
        scheduler.onEveryFiveMinutes();
        verify(normalizer, times(2)).normalize();
    }

    /**
     * 첫 트리거 예외가 이후 5분 점검을 중단하지 않도록 격리합니다.
     * 오류는 로그에 보존하고 다음 정상화 시도를 진행할 수 있어야 합니다.
     * 운영상의 결제 확정은 트리거 클래스가 담당하지 않습니다.
     */
    @Test
    void schedulerExceptionDoesNotKillNextTrigger() {
        when(normalizer.normalize())
                .thenThrow(new IllegalStateException("temporary error"))
                .thenReturn(result("recovered"));
        assertDoesNotThrow(scheduler::onApplicationReady);
        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(normalizer, times(2)).normalize();
    }

    /**
     * Cron은 UTC 기준 매 5분이며 test 프로필에서 제외되어야 합니다.
     * 상위 enabled와 payment-webhook-enabled를 모두 명시적으로 켜야 실행됩니다.
     * 실수로 운영/테스트에서 결제 PG 호출이 시작되지 않도록 애노테이션을 확인합니다.
     */
    @Test
    void cronAndFeatureFlagsAreExplicit() throws Exception {
        Scheduled scheduled = PaymentWebhookReconciliationScheduler.class
                .getMethod("onEveryFiveMinutes").getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("0 */5 * * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        ConditionalOnProperty condition = PaymentWebhookReconciliationScheduler.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("app.scheduler", condition.prefix());
        assertArrayEquals(new String[]{"enabled", "payment-webhook-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertArrayEquals(new String[]{"!test"},
                PaymentWebhookReconciliationScheduler.class.getAnnotation(Profile.class).value());
    }

    /**
     * 로그 출력 검증에 사용할 기본 결과 객체를 생성합니다.
     * 실제 실행권 선점이나 외부 복구를 수행하지 않습니다.
     * 테스트별 오류 상태만 지정하도록 구성합니다.
     */
    private PaymentWebhookReconciliationNormalizationService.Result result(String error) {
        return new PaymentWebhookReconciliationNormalizationService.Result(
                Instant.parse("2026-11-11T01:05:00Z"),
                null, null, error, false);
    }
}
