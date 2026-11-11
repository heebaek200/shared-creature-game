package com.team.sharedcreature.quest.scheduler;

import com.team.sharedcreature.quest.application.PhotoRetentionNormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 사진 정리의 기동·5분 트리거가 공통 정상화에 위임하는지 검증합니다.
 * DB나 Spring 스케줄 스레드는 사용하지 않고 직접 메서드를 호출합니다.
 * 기존 시즌 Scheduler와 별도 작업임을 확인합니다.
 */
class PhotoRetentionSchedulerTest {

    private final PhotoRetentionNormalizationService normalizer = mock(PhotoRetentionNormalizationService.class);
    private final PhotoRetentionScheduler scheduler = new PhotoRetentionScheduler(normalizer);

    /**
     * 서버 기동과 5분 점검은 같은 서비스 메서드를 사용합니다.
     * 한 인스턴스에 서로 다른 트리거가 와도 실행권 처리 계층이 동일합니다.
     * 업무 로직은 Scheduler 클래스에 존재하지 않습니다.
     */
    @Test
    void startupAndPeriodicTriggerUseOneNormalizer() {
        scheduler.onApplicationReady();
        scheduler.onEveryFiveMinutes();
        verify(normalizer, times(2)).normalize();
    }

    /**
     * 사진 정리 DB 장애가 Cron 처리 스레드까지 전파되지 않게 합니다.
     * 실패 후에도 다음 5분에 정상화가 호출됩니다.
     * 실제 실행권 오류 기록은 공통 JobRunner의 별도 테스트 대상입니다.
     */
    @Test
    void oneTriggerFailureDoesNotBreakLaterChecks() {
        when(normalizer.normalize())
                .thenThrow(new IllegalStateException("db offline"))
                .thenReturn(new PhotoRetentionNormalizationService.Result(
                        Instant.parse("2026-11-10T09:05:00Z"), null, null, "recovered"));
        assertDoesNotThrow(scheduler::onApplicationReady);
        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(normalizer, times(2)).normalize();
    }

    /**
     * 스케줄 시각을 UTC로 고정해야 다중 인스턴스에서 논리 실행권이 일치합니다.
     * 매 5분 0초에 호출되도록 애노테이션의 Cron 식을 확인합니다.
     * 첫 기동 실행은 별도의 ApplicationReadyEvent로 제공됩니다.
     */
    @Test
    void cronUsesUtcFiveMinuteBoundary() throws NoSuchMethodException {
        Method method = PhotoRetentionScheduler.class.getMethod("onEveryFiveMinutes");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("0 */5 * * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }
}
