package com.team.sharedcreature.season.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 시즌 종료 정상화의 조회·작업 순서와 실패 격리를 검증합니다.
 * 동일 서버의 중첩 트리거, 누락 작업 재조회, 인스턴스 ID 고정을 검사합니다.
 * DB Lock 및 SchedulerExecution의 Commit은 별도 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonCloseNormalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-30T00:00:00Z");

    @Mock private SeasonRepository seasonRepository;
    @Mock private SeasonCloseJobService closeJobService;
    private SeasonCloseNormalizationService service;

    /**
     * 매 테스트마다 고정 UTC 시각과 독립된 정상화 서비스를 만듭니다.
     * 매번 새로운 UUID를 가지지만 같은 서비스의 호출은 동일한 ID를 사용합니다.
     * Mock Repository는 현재 시각과 후보 시즌 ID를 반환하도록 구성합니다.
     */
    @BeforeEach
    void setUp() {
        service = new SeasonCloseNormalizationService(
                seasonRepository, closeJobService, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    /**
     * DB가 반환한 종료 대상 시즌을 순서대로 실행합니다.
     * 서로 다른 상태의 실제 탐색 조건은 Repository 쿼리에서 검증합니다.
     * 성공 및 선점 실패를 각각 집계합니다.
     */
    @Test
    void scansCandidatesInOrderAndCountsOutcomes() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW))
                .thenReturn(List.of(11L, 12L, 13L));
        when(closeJobService.runSeasonClose(eq(11L), anyString()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SUCCEEDED);
        when(closeJobService.runSeasonClose(eq(12L), anyString()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);
        when(closeJobService.runSeasonClose(eq(13L), anyString()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SUCCEEDED);

        // 목록의 현재 실행 순서를 변경하거나 임의의 기간을 소급 생성하지 않습니다.
        var result = service.normalize();
        assertEquals(3, result.examined());
        assertEquals(2, result.succeeded());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failed());
        assertFalse(result.alreadyRunning());

        var ordered = inOrder(closeJobService);
        ordered.verify(closeJobService).runSeasonClose(eq(11L), anyString());
        ordered.verify(closeJobService).runSeasonClose(eq(12L), anyString());
        ordered.verify(closeJobService).runSeasonClose(eq(13L), anyString());
    }

    /**
     * 종료 대상이 없다면 어떤 Scheduler 실행권도 선점하지 않습니다.
     * 호출 결과에 처리 대상 0건을 명확히 표시합니다.
     * 정상화 서비스는 DB에 임의의 시즌을 새로 만들지 않습니다.
     */
    @Test
    void noCandidatesDoesNotStartJobs() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW)).thenReturn(List.of());

        var result = service.normalize();
        assertEquals(0, result.examined());
        assertEquals(0, result.failed());
        verifyNoInteractions(closeJobService);
    }

    /**
     * 한 시즌이 실패해도 다음 종료 대상을 계속 처리합니다.
     * 실패 시 다음 트리거가 DB 상태를 바탕으로 다시 조회할 수 있어야 합니다.
     * 개별 예외가 모든 시즌의 정상화를 중단해서는 안 됩니다.
     */
    @Test
    void seasonFailureDoesNotStopOtherSeasons() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW)).thenReturn(List.of(1L, 2L));
        when(closeJobService.runSeasonClose(eq(1L), anyString()))
                .thenThrow(new IllegalStateException("Snapshot incomplete"));
        when(closeJobService.runSeasonClose(eq(2L), anyString()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SUCCEEDED);

        var result = service.normalize();
        assertEquals(1, result.failed());
        assertEquals(1, result.succeeded());
        verify(closeJobService).runSeasonClose(eq(2L), anyString());
    }

    /**
     * 서버 내부에서 5분 이벤트와 자정 이벤트가 겹쳐도 이중 실행하지 않습니다.
     * 첫 번째 실행 도중 호출되는 두 번째 정상화는 즉시 건너뜁니다.
     * 다른 JVM의 중복 처리는 SchedulerExecution의 DB 선점이 담당합니다.
     */
    @Test
    void nestedTriggerIsSkippedInSameJvm() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW)).thenReturn(List.of(1L));
        AtomicReference<SeasonCloseNormalizationService.NormalizationResult> nested = new AtomicReference<>();
        when(closeJobService.runSeasonClose(eq(1L), anyString())).thenAnswer(invocation -> {
            nested.set(service.normalize());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });

        var result = service.normalize();
        assertEquals(1, result.succeeded());
        assertTrue(nested.get().alreadyRunning());
        assertEquals(0, nested.get().examined());
        verify(seasonRepository, times(1)).findIdsNeedingCloseNormalization(NOW);
    }

    /**
     * DB 후보 조회 자체가 실패해도 실행 중 플래그가 반드시 해제됩니다.
     * 다음 주기 호출은 다시 조회를 수행할 수 있어야 합니다.
     * 영구적으로 점검이 중지되는 상황을 방지합니다.
     */
    @Test
    void queryFailureReleasesRunningFlag() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, service::normalize);
        assertFalse(service.normalize().alreadyRunning());
        verify(seasonRepository, times(2)).findIdsNeedingCloseNormalization(NOW);
    }

    /**
     * 같은 서비스 인스턴스의 실행은 동일한 ownerInstanceId를 사용합니다.
     * JVM이 재기동되면 새 UUID를 사용하므로 오래된 Lease와 구분됩니다.
     * 실행권 키의 논리 시각은 종료 Job Service가 시즌 endAt으로 결정합니다.
     */
    @Test
    void ownerInstanceIdIsStableWithinOneProcess() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW))
                .thenReturn(List.of(1L), List.of(1L));
        when(closeJobService.runSeasonClose(eq(1L), anyString()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SUCCEEDED);

        service.normalize();
        service.normalize();

        ArgumentCaptor<String> owners = ArgumentCaptor.forClass(String.class);
        verify(closeJobService, times(2)).runSeasonClose(eq(1L), owners.capture());
        assertEquals(owners.getAllValues().get(0), owners.getAllValues().get(1));
        assertTrue(owners.getAllValues().get(0).startsWith("season-close-"));
        assertTrue(owners.getAllValues().get(0).length() <= 100);
    }

    /**
     * 실제 정상화 대상 조회에는 주입된 Clock의 현재 시각을 사용합니다.
     * 로컬 JVM의 기본 타임존이나 서버 시작 시각에 의존하지 않습니다.
     * 테스트 Clock은 UTC로 고정돼 있으므로 경계 결과가 반복 가능합니다.
     */
    @Test
    void passesInjectedCurrentTimeToRepository() {
        when(seasonRepository.findIdsNeedingCloseNormalization(NOW)).thenReturn(List.of());
        service.normalize();

        verify(seasonRepository).findIdsNeedingCloseNormalization(NOW);
    }

    /**
     * 외부에서 시작한 트랜잭션 안에서는 정상화 실행을 차단합니다.
     * 성공 이력이 업무 Commit보다 먼저 확정되는 상황을 예방합니다.
     * 테스트가 끝나면 ThreadLocal 트랜잭션 상태를 원래대로 복원합니다.
     */
    @Test
    void rejectsAmbientTransaction() {
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, service::normalize);
            verifyNoInteractions(seasonRepository, closeJobService);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
    }
}
