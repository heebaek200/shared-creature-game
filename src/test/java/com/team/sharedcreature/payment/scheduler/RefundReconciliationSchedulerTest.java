package com.team.sharedcreature.payment.scheduler;

import com.team.sharedcreature.payment.application.RefundReconciliationNormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 환불 상태 복구 트리거가 중복 업무를 직접 실행하지 않는지 확인합니다.
 * 실제 PG 접속 및 DB 변경은 하지 않는 단위 테스트입니다.
 * 스케줄 및 플래그 설정은 런타임 리플렉션으로 확인합니다.
 */
class RefundReconciliationSchedulerTest {
    private final RefundReconciliationNormalizationService normalizer = mock(RefundReconciliationNormalizationService.class);
    private final RefundReconciliationScheduler scheduler = new RefundReconciliationScheduler(normalizer);

    /**
     * 기동 이벤트와 5분 Cron이 동일한 정상화 Service를 호출합니다.
     * 별도 파일·PG 상태 변경이 Scheduler 클래스에 들어가지 않게 합니다.
     * 실제 실행권 및 멱등 판단은 다음 계층으로 위임합니다.
     */
    @Test
    void delegatesBothTriggersToOneNormalizer() {
        scheduler.onApplicationReady();
        scheduler.onEveryFiveMinutes();
        verify(normalizer, times(2)).normalize();
    }

    /**
     * 첫 번째 트리거 실패가 두 번째 트리거 실행을 막지 않습니다.
     * 정시와 재기동 경로 모두 안전하게 다음 점검을 수행합니다.
     * 실패 실행 이력 기록은 별도의 Runner 테스트 책임입니다.
     */
    @Test
    void catchesTriggerFailureAndAllowsNextAttempt() {
        when(normalizer.normalize()).thenThrow(new IllegalStateException("db offline"))
                .thenReturn(new RefundReconciliationNormalizationService.Result(
                        Instant.parse("2026-11-11T03:05:00Z"), null, null, "recovering", false));
        assertDoesNotThrow(scheduler::onApplicationReady);
        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(normalizer, times(2)).normalize();
    }

    /**
     * 여러 서버가 동일한 UTC 5분 경계를 사용하도록 Cron을 검증합니다.
     * 주어진 시각의 실제 실행권 논리 키 계산은 Normalizer 테스트가 확인합니다.
     * 시스템 로컬 시간대의 영향을 받지 않습니다.
     */
    @Test
    void usesUtcFiveMinuteCron() throws NoSuchMethodException {
        Method method = RefundReconciliationScheduler.class.getMethod("onEveryFiveMinutes");
        Scheduled annotation = method.getAnnotation(Scheduled.class);
        assertNotNull(annotation);
        assertEquals("0 */5 * * * *", annotation.cron());
        assertEquals("UTC", annotation.zone());
    }

    /**
     * 환불 업무 어댑터가 준비되기 전 기능 플래그를 통해 비활성화합니다.
     * 전역 Scheduler가 활성화돼도 환불 플래그가 false면 Bean은 생성되지 않습니다.
     * 누락된 #22 구현체로 테스트·개발 서버 기동이 실패하지 않게 합니다.
     */
    @Test
    void schedulerHasExplicitOptInFlag() {
        ConditionalOnProperty annotation = RefundReconciliationScheduler.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation);
        assertEquals("app.scheduler", annotation.prefix());
        assertEquals("true", annotation.havingValue());
        assertTrue(Arrays.asList(annotation.name()).containsAll(
                java.util.List.of("enabled", "refund-reconciliation-enabled")));
    }
}
