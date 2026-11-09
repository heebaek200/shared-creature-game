package com.team.sharedcreature.season.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerExecutionService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 시즌 종료의 실제 Scheduler 실행 흐름을 DB 없이 검증합니다.
 * 공통 실행기는 실제 객체로 사용하고 DB 관련 Service는 Mock으로 대체합니다.
 * 성공 기록, Lease 갱신, 업무 예외와 중단 후 멱등 복구를 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonCloseJobServiceTest {

    private static final Long SEASON_ID = 71L;
    private static final Instant END = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant NOW = END.plus(Duration.ofHours(1));
    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final String OWNER = "local-instance-1";
    private static final SchedulerClaimTicket TICKET = new SchedulerClaimTicket(
            123L, "SEASON_CLOSE", END, OWNER, 1
    );

    @Mock SeasonRepository seasonRepository;
    @Mock SeasonTransitionService transitionService;
    @Mock SeasonGroupSnapshotService groupSnapshotService;
    @Mock SeasonMemberSnapshotService memberSnapshotService;
    @Mock SeasonCloseFinalizationService finalizationService;
    @Mock SchedulerExecutionService claimService;
    @Mock SchedulerExecutionLifecycleService lifecycleService;
    @Mock Season season;

    private SeasonCloseJobService service;

    /**
     * 서비스와 UTC 고정 Clock을 초기화합니다.
     * SchedulerJobRunner는 Mock이 아니라 실제 코드를 실행합니다.
     * 테스트별 업무 Mock 상호작용은 MockitoExtension에서 격리됩니다.
     */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SchedulerJobRunner runner = new SchedulerJobRunner(claimService, lifecycleService);
        service = new SeasonCloseJobService(
                seasonRepository, transitionService,
                groupSnapshotService, memberSnapshotService,
                finalizationService, runner, lifecycleService, clock
        );
    }

    /**
     * 시즌 종료 시각 이전에는 Scheduler 실행 이력을 생성하지 않습니다.
     * 호출 시각이 아니라 실제 시즌 종료 경계에서만 작업을 시작합니다.
     * 시즌 상태를 변경하거나 Snapshot Service를 호출해서는 안 됩니다.
     */
    @Test
    void notDueSeasonDoesNotClaim() {
        when(seasonRepository.findById(SEASON_ID)).thenReturn(Optional.of(season));
        when(season.getEndAt()).thenReturn(NOW.plusSeconds(3600));

        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED,
                service.runSeasonClose(SEASON_ID, OWNER));
        verifyNoInteractions(claimService, lifecycleService, transitionService,
                groupSnapshotService, memberSnapshotService, finalizationService);
    }

    /**
     * 다른 서버가 실행권을 보유하면 모든 업무 실행을 건너뜁니다.
     * 동일 jobType과 시즌 종료 시각을 중복 실행하지 않습니다.
     * 그룹·개인 Snapshot과 종료 상태에는 어떠한 변경도 없습니다.
     */
    @Test
    void duplicateClaimSkipsBusiness() {
        // 선점 실패 시 현재 상태 조회는 실행되지 않으므로 필요한 값만 Stub합니다.
        when(seasonRepository.findById(SEASON_ID)).thenReturn(Optional.of(season));
        when(season.getEndAt()).thenReturn(END);
        when(claimService.tryClaim("SEASON_CLOSE", END, OWNER, LEASE))
                .thenReturn(Optional.empty());

        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED,
                service.runSeasonClose(SEASON_ID, OWNER));
        verifyNoInteractions(transitionService, groupSnapshotService,
                memberSnapshotService, finalizationService, lifecycleService);
    }

    /**
     * 그룹 배치 2회와 개인 배치 1회가 모두 끝나야 성공을 기록합니다.
     * Snapshot 배치 호출 사이에 유효한 실행권으로 Lease를 갱신합니다.
     * 결과 JSON은 논리 시즌 ID와 Snapshot 대상 건수를 포함합니다.
     */
    @Test
    void severalBatchesFinishBeforeSchedulerSuccess() {
        prepareSeason("CLOSING");
        allowClaim();
        when(groupSnapshotService.writeNextBatch(SEASON_ID)).thenReturn(
                new SeasonGroupSnapshotService.Progress(101, 100, 1),
                new SeasonGroupSnapshotService.Progress(101, 1, 0)
        );
        when(memberSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonMemberSnapshotService.Progress(3, 3, 0));
        when(finalizationService.finishIfSnapshotsComplete(SEASON_ID)).thenReturn(true);

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                service.runSeasonClose(SEASON_ID, OWNER));

        // 세 독립 업무 서비스가 모두 끝나야 Scheduler 성공 상태를 갱신합니다.
        var order = inOrder(transitionService, groupSnapshotService,
                memberSnapshotService, finalizationService, lifecycleService);
        order.verify(transitionService).beginClosingIfExpired(SEASON_ID);
        order.verify(groupSnapshotService, times(2)).writeNextBatch(SEASON_ID);
        order.verify(memberSnapshotService).writeNextBatch(SEASON_ID);
        order.verify(finalizationService).finishIfSnapshotsComplete(SEASON_ID);
        order.verify(lifecycleService).markSucceeded(TICKET,
                "{\"seasonId\":71,\"groupSnapshots\":101,\"memberSnapshots\":3,\"alreadyEnded\":false}");
        verify(lifecycleService, times(5)).renewLease(TICKET, LEASE);
        verify(lifecycleService, never()).markFailed(any(), any(), any());
    }

    /**
     * SCHEDULED에서 만료된 시즌도 정상 종료 이력을 성공 처리합니다.
     * 그룹과 개인 Snapshot 대상이 모두 0건인 경우를 검증합니다.
     * Snapshot 없이 ENDED 처리가 완료돼야 SUCCEEDED를 기록합니다.
     */
    @Test
    void scheduledClosingWithoutSnapshotsIsSuccessful() {
        prepareSeason("CLOSING");
        allowClaim();
        when(groupSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonGroupSnapshotService.Progress(0, 0, 0));
        when(memberSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonMemberSnapshotService.Progress(0, 0, 0));
        when(finalizationService.finishIfSnapshotsComplete(SEASON_ID)).thenReturn(true);

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                service.runSeasonClose(SEASON_ID, OWNER));
        verify(lifecycleService).markSucceeded(TICKET,
                "{\"seasonId\":71,\"groupSnapshots\":0,\"memberSnapshots\":0,\"alreadyEnded\":false}");
    }

    /**
     * ENDED Commit 직후 프로세스가 종료된 경우 성공 이력만 복구합니다.
     * 기존 그룹 및 개인 Snapshot을 다시 조회하거나 저장하지 않습니다.
     * 논리 실행권을 획득해 Scheduler 이력만 성공으로 확정합니다.
     */
    @Test
    void alreadyEndedSeasonRepairsSchedulerSuccess() {
        prepareSeason("ENDED");
        allowClaim();

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                service.runSeasonClose(SEASON_ID, OWNER));
        verifyNoInteractions(transitionService, groupSnapshotService,
                memberSnapshotService, finalizationService);
        verify(lifecycleService).markSucceeded(TICKET,
                "{\"seasonId\":71,\"groupSnapshots\":0,\"memberSnapshots\":0,\"alreadyEnded\":true}");
        verify(lifecycleService, never()).renewLease(any(), any());
    }

    /**
     * 그룹 Snapshot 저장 실패 시 개인 단계와 ENDED 확정을 실행하지 않습니다.
     * 실행기의 FAILED 기록 경로가 실제 예외를 보존해야 합니다.
     * 이전 배치의 Commit은 DB에 남아 다음 실행에서 복구할 수 있습니다.
     */
    @Test
    void groupBatchFailureStopsLaterStages() {
        prepareSeason("CLOSING");
        allowClaim();
        when(groupSnapshotService.writeNextBatch(SEASON_ID))
                .thenThrow(new IllegalStateException("group storage failed"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.runSeasonClose(SEASON_ID, OWNER));
        assertEquals("group storage failed", error.getMessage());
        verify(lifecycleService).markFailed(eq(TICKET), eq("JOB_EXECUTION_FAILED"),
                contains("group storage failed"));
        verifyNoInteractions(memberSnapshotService, finalizationService);
        verify(lifecycleService, never()).markSucceeded(any(), any());
    }

    /**
     * 그룹 Snapshot은 끝났지만 개인 Snapshot 저장이 실패하는 상황입니다.
     * 최종 종료를 시도하지 않고 실패로 남겨 다음 정규화에서 재개합니다.
     * 성공 이력을 기록하면 안 된다는 규칙을 검증합니다.
     */
    @Test
    void memberBatchFailurePreventsEnded() {
        prepareSeason("CLOSING");
        allowClaim();
        when(groupSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonGroupSnapshotService.Progress(1, 0, 0));
        when(memberSnapshotService.writeNextBatch(SEASON_ID))
                .thenThrow(new IllegalStateException("missing profile"));

        assertThrows(IllegalStateException.class,
                () -> service.runSeasonClose(SEASON_ID, OWNER));
        verifyNoInteractions(finalizationService);
        verify(lifecycleService, never()).markSucceeded(any(), any());
        verify(lifecycleService).markFailed(eq(TICKET), eq("JOB_EXECUTION_FAILED"),
                contains("missing profile"));
    }

    /**
     * 누락분이 남았는데 저장된 행이 0건이라면 무한 루프를 중단합니다.
     * 진전이 없는 작업의 성공을 기록하지 않습니다.
     * 관리자가 원인을 조사할 수 있도록 FAILED 이력을 시도합니다.
     */
    @Test
    void noProgressIsNotMistakenForCompletion() {
        prepareSeason("CLOSING");
        allowClaim();
        when(groupSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonGroupSnapshotService.Progress(100, 0, 100));

        assertThrows(IllegalStateException.class,
                () -> service.runSeasonClose(SEASON_ID, OWNER));
        verify(groupSnapshotService, times(1)).writeNextBatch(SEASON_ID);
        verify(lifecycleService, never()).markSucceeded(any(), any());
        verifyNoInteractions(memberSnapshotService, finalizationService);
    }

    /**
     * 최종 Snapshot 검증에서 오류가 발생하면 성공으로 확정하지 않습니다.
     * 앞선 배치들은 별도의 Commit이므로 재시도에 안전해야 합니다.
     * Scheduler 성공 기록을 방지하고 FAILED 기록을 남깁니다.
     */
    @Test
    void finalizationFailureDoesNotMarkSucceeded() {
        prepareSeason("CLOSING");
        allowClaim();
        when(groupSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonGroupSnapshotService.Progress(0, 0, 0));
        when(memberSnapshotService.writeNextBatch(SEASON_ID))
                .thenReturn(new SeasonMemberSnapshotService.Progress(0, 0, 0));
        when(finalizationService.finishIfSnapshotsComplete(SEASON_ID))
                .thenThrow(new IllegalStateException("snapshot mismatch"));

        assertThrows(IllegalStateException.class,
                () -> service.runSeasonClose(SEASON_ID, OWNER));
        verify(lifecycleService).markFailed(eq(TICKET), eq("JOB_EXECUTION_FAILED"),
                contains("snapshot mismatch"));
        verify(lifecycleService, never()).markSucceeded(any(), any());
    }

    /**
     * 실행권 Lease를 잃었다면 추가 업무를 실행하지 않습니다.
     * Heartbeat 실패가 처음 업무 INSERT보다 먼저 검출돼야 합니다.
     * 다른 인스턴스의 재선점 이후 오래된 실행자의 중복 처리를 줄입니다.
     */
    @Test
    void expiredLeaseAbortsBeforeWriting() {
        prepareSeason("CLOSING");
        allowClaim();
        doThrow(new IllegalStateException("expired lease"))
                .when(lifecycleService).renewLease(TICKET, LEASE);

        assertThrows(IllegalStateException.class,
                () -> service.runSeasonClose(SEASON_ID, OWNER));
        verifyNoInteractions(transitionService, groupSnapshotService,
                memberSnapshotService, finalizationService);
        verify(lifecycleService, never()).markSucceeded(any(), any());
    }

    /**
     * 지정한 시즌의 종료 시각과 DB 상태를 공통 Mock으로 준비합니다.
     * 두 번 조회해도 같은 시즌 정보를 반환합니다.
     * 상태는 테스트마다 ENDED 또는 CLOSING 등으로 구분합니다.
     */
    private void prepareSeason(String status) {
        when(seasonRepository.findById(SEASON_ID)).thenReturn(Optional.of(season));
        when(season.getEndAt()).thenReturn(END);
        when(season.getStatus()).thenReturn(status);
    }

    /**
     * Scheduler 실행권을 정상적으로 획득하는 상황을 준비합니다.
     * 논리 작업 시각과 인스턴스 ID는 실제 호출 조건과 동일합니다.
     * 콜백 실행 전에 한 번만 호출돼야 합니다.
     */
    private void allowClaim() {
        when(claimService.tryClaim("SEASON_CLOSE", END, OWNER, LEASE))
                .thenReturn(Optional.of(TICKET));
    }
}
