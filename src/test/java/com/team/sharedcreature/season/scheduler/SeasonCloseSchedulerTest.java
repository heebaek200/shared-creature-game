package com.team.sharedcreature.season.scheduler;

import com.team.sharedcreature.season.application.SeasonLifecycleNormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 기존 시즌 종료 스케줄러가 시즌 전체 정상화로 변경됐는지 검증합니다.
 * 기동·KST 자정·5분 주기가 동일한 Service에 위임되는지 확인합니다.
 * 실제 DB 접근과 Clock 변경은 정상화 계층의 테스트에서 확인합니다.
 */
class SeasonCloseSchedulerTest {

    private final SeasonLifecycleNormalizationService normalizer =
            mock(SeasonLifecycleNormalizationService.class);
    private final SeasonCloseScheduler scheduler = new SeasonCloseScheduler(normalizer);

    /**
     * ApplicationReadyEvent가 시즌 전체 정상화를 호출하는지 확인합니다.
     * 종료 전용 정상화를 별도 트리거하지 않아 중복 실행을 방지합니다.
     * 업무 처리는 Service에서 맡고 스케줄러는 위임만 수행합니다.
     */
    @Test
    void startupDelegatesToLifecycle() {
        scheduler.onApplicationReady();
        verify(normalizer).normalize();
        verifyNoMoreInteractions(normalizer);
    }

    /**
     * KST 자정 트리거가 시즌 전체 정상화를 호출하는지 확인합니다.
     * 4주 시즌 종료와 새 시즌 생성이 같은 진입점에서 이루어집니다.
     * 같은 시간에 5분 트리거가 실행돼도 Service에서 중복을 막습니다.
     */
    @Test
    void midnightDelegatesToLifecycle() {
        scheduler.onKstMidnight();
        verify(normalizer).normalize();
    }

    /**
     * 5분 주기 트리거가 시즌 전체 정상화를 호출하는지 확인합니다.
     * 다음 배치에서 이미 생성된 알·점수와 부화 기한을 보존합니다.
     * 스케줄러 자체는 DB 상태를 직접 변경하지 않습니다.
     */
    @Test
    void fiveMinuteCheckDelegatesToLifecycle() {
        scheduler.onEveryFiveMinutes();
        verify(normalizer).normalize();
    }

    /**
     * 조회·초기화 오류가 발생해도 스케줄러 메서드는 예외를 숨겨 재점검을 유지합니다.
     * 이미 Commit된 업무 데이터를 되돌리는 로직은 트리거에 존재하지 않습니다.
     * 오류는 로그에 기록되고 다음 재점검에서 상태를 다시 읽습니다.
     */
    @Test
    void triggerContainsInfrastructureFailure() {
        when(normalizer.normalize()).thenThrow(new IllegalStateException("DB unavailable"));

        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(normalizer).normalize();
    }

    /**
     * 기존 세 트리거의 시각 설정이 변경되지 않았는지 확인합니다.
     * Cron에 Asia/Seoul 타임존을 명시해 호스트 설정에 의존하지 않습니다.
     * 5분 점검은 자정 트리거와 별도로 등록됩니다.
     */
    @Test
    void cronExpressionsKeepKstTimeZone() throws NoSuchMethodException {
        Method midnight = SeasonCloseScheduler.class.getMethod("onKstMidnight");
        Method fiveMinutes = SeasonCloseScheduler.class.getMethod("onEveryFiveMinutes");
        Scheduled midnightScheduled = midnight.getAnnotation(Scheduled.class);
        Scheduled fiveMinuteScheduled = fiveMinutes.getAnnotation(Scheduled.class);

        assertNotNull(midnightScheduled);
        assertNotNull(fiveMinuteScheduled);
        assertEquals("0 0 0 * * *", midnightScheduled.cron());
        assertEquals("0 */5 * * * *", fiveMinuteScheduled.cron());
        assertEquals("Asia/Seoul", midnightScheduled.zone());
        assertEquals("Asia/Seoul", fiveMinuteScheduled.zone());
    }
}
