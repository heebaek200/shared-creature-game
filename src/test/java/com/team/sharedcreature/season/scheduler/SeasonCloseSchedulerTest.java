package com.team.sharedcreature.season.scheduler;

import com.team.sharedcreature.quest.application.QuestAssignmentNormalizationService;
import com.team.sharedcreature.season.application.CreatureHatchingNormalizationService;
import com.team.sharedcreature.season.application.SeasonLifecycleNormalizationService;
import org.junit.jupiter.api.BeforeEach;
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
    private final QuestAssignmentNormalizationService questNormalizer =
            mock(QuestAssignmentNormalizationService.class);
    private final CreatureHatchingNormalizationService hatchingNormalizer =
            mock(CreatureHatchingNormalizationService.class);
    private final SeasonCloseScheduler scheduler =
            new SeasonCloseScheduler(normalizer, questNormalizer, hatchingNormalizer);

    /**
     * 실제 시즌 정상화 결과처럼 ACTIVE 시즌 ID를 반환하도록 설정합니다.
     * 시즌 준비가 완료된 경우에만 퀘스트 정상화가 수행되는지 검증합니다.
     * 테스트마다 Mock 호출 이력은 JUnit 인스턴스 생명주기에 따라 분리됩니다.
     */
    @BeforeEach
    void setUp() {
        when(normalizer.normalize()).thenReturn(new SeasonLifecycleNormalizationService.Result(
                SeasonLifecycleNormalizationService.Outcome.ACTIVE, 42L, 0, 0, null));
    }

    /**
     * ApplicationReadyEvent가 시즌 전체 정상화를 호출하는지 확인합니다.
     * 종료 전용 정상화를 별도 트리거하지 않아 중복 실행을 방지합니다.
     * 업무 처리는 Service에서 맡고 스케줄러는 위임만 수행합니다.
     */
    @Test
    void startupDelegatesToLifecycle() {
        scheduler.onApplicationReady();
        verify(normalizer).normalize();
        verify(questNormalizer).normalizeForActiveSeason(42L);
        verify(hatchingNormalizer).normalizeForActiveSeason(42L);
        verifyNoMoreInteractions(normalizer, questNormalizer, hatchingNormalizer);
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
        verify(questNormalizer).normalizeForActiveSeason(42L);
        verify(hatchingNormalizer).normalizeForActiveSeason(42L);
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
        verify(questNormalizer).normalizeForActiveSeason(42L);
        verify(hatchingNormalizer).normalizeForActiveSeason(42L);
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
        verifyNoInteractions(questNormalizer, hatchingNormalizer);
    }

    /**
     * 시즌의 모든 초기화가 끝나지 않았다면 퀘스트 배정을 건너뜁니다.
     * 다음 5분 점검에서 시즌이 ACTIVE가 된 이후에만 퀘스트 정상화를 시작합니다.
     * 부분적으로 생성된 그룹 시즌에서 배정이 노출되지 않아야 합니다.
     */
    @Test
    void waitsForSeasonActivationBeforeAssigningQuests() {
        when(normalizer.normalize()).thenReturn(new SeasonLifecycleNormalizationService.Result(
                SeasonLifecycleNormalizationService.Outcome.MORE_GROUPS, 42L, 1, 100, null));
        scheduler.onEveryFiveMinutes();
        verifyNoInteractions(questNormalizer, hatchingNormalizer);
    }

    /**
     * 퀘스트 정합화 중 예외가 발생해도 다음 Cron이 중단되지 않아야 합니다.
     * 이미 시즌 정상화가 성공한 결과는 유지합니다.
     * 실패한 배정은 다음 5분 실행에서 별도로 복구합니다.
     */
    @Test
    void questFailureDoesNotStopSchedulerThread() {
        when(questNormalizer.normalizeForActiveSeason(42L))
                .thenThrow(new IllegalStateException("Quest DB down"));
        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(normalizer).normalize();
        verify(questNormalizer).normalizeForActiveSeason(42L);
        verify(hatchingNormalizer).normalizeForActiveSeason(42L);
    }

    /**
     * 퀘스트 배정이 실패해도 현재 시즌의 부화 대상은 계속 처리합니다.
     * 두 업무의 실패·재시도 단위를 서로 독립적으로 유지합니다.
     * 실패한 퀘스트는 다음 점검에서 별도로 재시도됩니다.
     */
    @Test
    void questFailureStillRunsHatching() {
        when(questNormalizer.normalizeForActiveSeason(42L))
                .thenThrow(new IllegalStateException("Quest DB unavailable"));

        // 전체 스케줄러 호출이 예외를 밖으로 전파하지 않습니다.
        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(hatchingNormalizer).normalizeForActiveSeason(42L);
    }

    /**
     * 부화 작업 자체가 실패하더라도 이미 수행된 퀘스트 정상화는 되돌리지 않습니다.
     * 다음 5분 점검에서 미부화 그룹을 다시 검색하도록 예외를 로그로 남깁니다.
     * 새 작업이 기존 Scheduler 트리거의 생존성을 깨지 않는지 확인합니다.
     */
    @Test
    void hatchingFailureDoesNotStopSchedulerThread() {
        when(hatchingNormalizer.normalizeForActiveSeason(42L))
                .thenThrow(new IllegalStateException("Creature DB unavailable"));

        // 크리처 복구 장애는 동일 Cron 메서드를 종료시키지 않습니다.
        assertDoesNotThrow(scheduler::onEveryFiveMinutes);
        verify(questNormalizer).normalizeForActiveSeason(42L);
        verify(hatchingNormalizer).normalizeForActiveSeason(42L);
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
