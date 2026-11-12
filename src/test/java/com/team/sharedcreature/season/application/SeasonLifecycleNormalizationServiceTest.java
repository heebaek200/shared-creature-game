package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 종료·생성·초기화·활성화 서비스의 트랜잭션 간 실행 순서를 검증합니다.
 * 대기 및 실패 분기는 후속 업무를 호출하지 않으며 기존 기록을 보존해야 합니다.
 * 실제 DB Lock과 Commit의 원자성은 MySQL 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonLifecycleNormalizationServiceTest {

    private static final long SEASON_ID = 22L;
    private static final Instant START = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant END = Instant.parse("2026-12-27T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @Mock private SeasonCloseNormalizationService closeNormalizationService;
    @Mock private SeasonCreationService creationService;
    @Mock private SeasonGroupInitializationService initializationService;
    @Mock private SeasonActivationService activationService;
    @Mock private SeasonRepository seasonRepository;

    private MutableClock clock;
    private SeasonLifecycleNormalizationService service;

    /**
     * 테스트마다 독립된 정상화 서비스와 고정 시각을 구성합니다.
     * 실제 업무 서비스 대신 Mock을 사용해 실행 순서만 검증합니다.
     * 직접 SQL이나 Scheduler를 실행하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new SeasonLifecycleNormalizationService(
                closeNormalizationService, creationService, initializationService,
                activationService, seasonRepository, clock
        );
    }

    /**
     * 이전 시즌 종료, 새 시즌 생성, 그룹 초기화, 활성화 순서를 검증합니다.
     * 각 트랜잭션이 정상 반환된 다음 다음 서비스가 호출돼야 합니다.
     * 마지막에는 ACTIVE 시즌의 유효한 시간 구간까지 확인합니다.
     */
    @Test
    void runsCloseCreationInitializationActivationInOrder() {
        allowClosingAndCreation(SeasonCreationService.Outcome.CREATED);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.COMPLETE, 3
        ));
        when(activationService.activateIfInitializationComplete(SEASON_ID)).thenReturn(activation(
                SeasonActivationService.Outcome.ACTIVATED
        ));
        when(seasonRepository.findById(SEASON_ID)).thenReturn(Optional.of(season("ACTIVE")));

        SeasonLifecycleNormalizationService.Result result = service.normalize();

        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, result.outcome());
        assertEquals(SEASON_ID, result.seasonId());
        assertEquals(1, result.batches());
        assertEquals(3, result.processedGroups());
        InOrder order = inOrder(closeNormalizationService, creationService, initializationService,
                activationService, seasonRepository);
        order.verify(closeNormalizationService).normalize();
        order.verify(creationService).createCurrentScheduledIfMissing();
        order.verify(initializationService).initializeNextBatch(SEASON_ID);
        order.verify(activationService).activateIfInitializationComplete(SEASON_ID);
        order.verify(seasonRepository).findById(SEASON_ID);
    }

    /**
     * 이미 ACTIVE인 현재 시즌은 그룹 알을 다시 생성하지 않습니다.
     * 그룹 초기화 서비스의 NOT_SCHEDULED 반환 후 활성화 상태를 조회합니다.
     * DB 시각 범위가 유효한 ACTIVE일 때만 정상 완료로 인정합니다.
     */
    @Test
    void alreadyActiveSeasonIsReused() {
        allowClosingAndCreation(SeasonCreationService.Outcome.ALREADY_EXISTS);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.NOT_SCHEDULED, 0
        ));
        when(activationService.activateIfInitializationComplete(SEASON_ID)).thenReturn(activation(
                SeasonActivationService.Outcome.ALREADY_ACTIVE
        ));
        when(seasonRepository.findById(SEASON_ID)).thenReturn(Optional.of(season("ACTIVE")));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, service.normalize().outcome());
        verify(initializationService, times(1)).initializeNextBatch(SEASON_ID);
        verify(activationService, times(1)).activateIfInitializationComplete(SEASON_ID);
    }

    /**
     * 그룹이 200개 있을 때 100개씩 분리된 배치를 호출합니다.
     * 정확히 100개를 처리한 뒤에는 추가 조회로 완료 여부를 확인합니다.
     * 3회 호출 뒤에만 활성화를 허용합니다.
     */
    @Test
    void hundredGroupBatchesRunUntilComplete() {
        allowClosingAndCreation(SeasonCreationService.Outcome.CREATED);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(
                batch(SeasonGroupInitializationService.Outcome.MORE_MAY_REMAIN, 100),
                batch(SeasonGroupInitializationService.Outcome.MORE_MAY_REMAIN, 100),
                batch(SeasonGroupInitializationService.Outcome.COMPLETE, 0)
        );
        when(activationService.activateIfInitializationComplete(SEASON_ID)).thenReturn(activation(
                SeasonActivationService.Outcome.ACTIVATED
        ));
        when(seasonRepository.findById(SEASON_ID)).thenReturn(Optional.of(season("ACTIVE")));

        SeasonLifecycleNormalizationService.Result result = service.normalize();

        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, result.outcome());
        assertEquals(3, result.batches());
        assertEquals(200, result.processedGroups());
        verify(initializationService, times(3)).initializeNextBatch(SEASON_ID);
    }

    /**
     * 한 번의 정상화에서 최대 10개 배치까지만 실행합니다.
     * 1,000개를 처리한 뒤에는 아직 남았을 수 있으므로 ACTIVE로 전환하지 않습니다.
     * 이후 5분 점검에서 잔여 그룹을 이어서 처리합니다.
     */
    @Test
    void maximumBatchLimitDefersActivation() {
        allowClosingAndCreation(SeasonCreationService.Outcome.CREATED);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.MORE_MAY_REMAIN, 100
        ));

        SeasonLifecycleNormalizationService.Result result = service.normalize();

        assertEquals(SeasonLifecycleNormalizationService.Outcome.MORE_GROUPS, result.outcome());
        assertEquals(10, result.batches());
        assertEquals(1000, result.processedGroups());
        verify(initializationService, times(10)).initializeNextBatch(SEASON_ID);
        verifyNoInteractions(activationService, seasonRepository);
    }

    /**
     * 이전 종료 작업에 실패가 있다면 다음 시즌 생성을 시작하지 않습니다.
     * 오류가 생긴 시즌의 CLOSING을 보존하고 다음 실행에서 재시도합니다.
     * 그룹 초기화와 활성화도 수행하지 않습니다.
     */
    @Test
    void closingFailureBlocksSeasonCreation() {
        when(closeNormalizationService.normalize()).thenReturn(new SeasonCloseNormalizationService.NormalizationResult(
                1, 0, 0, 1, false
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.WAITING_FOR_CLOSURE,
                service.normalize().outcome());
        verifyNoInteractions(creationService, initializationService, activationService, seasonRepository);
    }

    /**
     * 다른 실행자가 종료 Job의 Lease를 가진 경우 새 시즌 생성을 보류합니다.
     * 이미 일부 종료 트랜잭션이 Commit됐더라도 그 상태를 변경하지 않습니다.
     * 다음 점검에서 종료 이력이 완료됐는지 다시 확인합니다.
     */
    @Test
    void closingLeaseSkipBlocksCreation() {
        when(closeNormalizationService.normalize()).thenReturn(new SeasonCloseNormalizationService.NormalizationResult(
                1, 0, 1, 0, false
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.WAITING_FOR_CLOSURE,
                service.normalize().outcome());
        verifyNoInteractions(creationService, initializationService, activationService, seasonRepository);
    }

    /**
     * 종료 전용 정상화가 다른 스레드에서 실행 중이면 생성을 보류합니다.
     * 별도 경로의 운영 명령과 충돌하는 상황도 처리하지 않습니다.
     * 이번 정상화에서는 새 그룹·시즌을 만들지 않습니다.
     */
    @Test
    void closeNormalizerAlreadyRunningBlocksCreation() {
        when(closeNormalizationService.normalize()).thenReturn(new SeasonCloseNormalizationService.NormalizationResult(
                0, 0, 0, 0, true
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.WAITING_FOR_CLOSURE,
                service.normalize().outcome());
        verifyNoInteractions(creationService, initializationService, activationService, seasonRepository);
    }

    /**
     * 직전 시즌이 아직 ENDED가 아니면 다음 구간을 생성하지 않습니다.
     * 생성 Service의 WAITING_FOR_CLOSURE를 그대로 보존합니다.
     * 초기화와 활성화는 호출하지 않습니다.
     */
    @Test
    void creationWaitsForPreviousEndedCommit() {
        allowClosingAndCreation(SeasonCreationService.Outcome.WAITING_FOR_CLOSURE);

        assertEquals(SeasonLifecycleNormalizationService.Outcome.WAITING_FOR_CLOSURE,
                service.normalize().outcome());
        verifyNoInteractions(initializationService, activationService, seasonRepository);
    }

    /**
     * 최초 시즌 시작 전에는 초기화·활성화를 실행하지 않습니다.
     * 첫 시즌을 임의로 생성하거나 시작 시각을 앞당기지 않습니다.
     * 종료 정상화 후 시작 대기 결과를 반환합니다.
     */
    @Test
    void beforeFirstSeasonReturnsNotStarted() {
        allowClosingAndCreation(SeasonCreationService.Outcome.NOT_STARTED);

        assertEquals(SeasonLifecycleNormalizationService.Outcome.NOT_STARTED,
                service.normalize().outcome());
        verifyNoInteractions(initializationService, activationService, seasonRepository);
    }

    /**
     * 초기화 중 시즌 종료 시각을 넘어가면 추가 작업을 중단합니다.
     * 부화 기한을 새로 생성하거나 만료 시즌을 활성화하지 않습니다.
     * 다음 재점검에서 종료 정상화 Service가 만료 시즌을 처리합니다.
     */
    @Test
    void expiredInitializationDoesNotActivate() {
        allowClosingAndCreation(SeasonCreationService.Outcome.CREATED);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.EXPIRED, 0
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.EXPIRED,
                service.normalize().outcome());
        verifyNoInteractions(activationService, seasonRepository);
    }

    /**
     * 전체 초기화 검사가 INCOMPLETE이면 SCHEDULED를 유지합니다.
     * 기존 알과 선택 기한을 덮어써서 임의로 정상화를 완료하지 않습니다.
     * 다음 트리거에서 추가 초기화를 검사하도록 결과를 반환합니다.
     */
    @Test
    void missingInitializationPreventsActivation() {
        allowClosingAndCreation(SeasonCreationService.Outcome.CREATED);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.COMPLETE, 0
        ));
        when(activationService.activateIfInitializationComplete(SEASON_ID)).thenReturn(activation(
                SeasonActivationService.Outcome.INCOMPLETE
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.INCOMPLETE,
                service.normalize().outcome());
        verifyNoInteractions(seasonRepository);
    }

    /**
     * 다른 스레드가 종료 상태를 변경했다면 활성화하지 않습니다.
     * SCHEDULED가 아닌 CLOSING 또는 ENDED를 ACTIVE로 소급 변경하지 않습니다.
     * 다음 시즌 종료 정상화로 복구하도록 결과를 남깁니다.
     */
    @Test
    void seasonStateChangedDuringActivation() {
        allowClosingAndCreation(SeasonCreationService.Outcome.ALREADY_EXISTS);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.NOT_SCHEDULED, 0
        ));
        when(activationService.activateIfInitializationComplete(SEASON_ID)).thenReturn(activation(
                SeasonActivationService.Outcome.NOT_SCHEDULED
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.STATE_CHANGED,
                service.normalize().outcome());
        verifyNoInteractions(seasonRepository);
    }

    /**
     * ACTIVE 전환 직후라도 현재 시간이 시즌 종료 시각이면 완료로 판단하지 않습니다.
     * 유효 시간 검사를 마지막에 다시 하여 경계 시각의 잘못된 성공을 막습니다.
     * 현재 만료 시즌은 다음 5분 정상화에서 종료 처리합니다.
     */
    @Test
    void activeSeasonIsRecheckedAgainstActualClock() {
        allowClosingAndCreation(SeasonCreationService.Outcome.CREATED);
        when(initializationService.initializeNextBatch(SEASON_ID)).thenReturn(batch(
                SeasonGroupInitializationService.Outcome.COMPLETE, 0
        ));
        when(activationService.activateIfInitializationComplete(SEASON_ID)).thenReturn(activation(
                SeasonActivationService.Outcome.ACTIVATED
        ));
        when(seasonRepository.findById(SEASON_ID)).thenAnswer(invocation -> {
            clock.set(END);
            return Optional.of(season("ACTIVE"));
        });

        assertEquals(SeasonLifecycleNormalizationService.Outcome.EXPIRED,
                service.normalize().outcome());
    }

    /**
     * 중첩된 기동·5분 트리거에서 두 번째 호출은 업무를 수행하지 않습니다.
     * AtomicBoolean 진입 보호가 실제 재귀 호출도 건너뛰도록 합니다.
     * 첫 번째 정상화는 정상적으로 이어서 수행할 수 있습니다.
     */
    @Test
    void reentrantNormalizationIsSkipped() {
        when(closeNormalizationService.normalize()).thenAnswer(invocation -> {
            assertEquals(SeasonLifecycleNormalizationService.Outcome.ALREADY_RUNNING,
                    service.normalize().outcome());
            return closedOk();
        });
        when(creationService.createCurrentScheduledIfMissing()).thenReturn(new SeasonCreationService.CreationResult(
                SeasonCreationService.Outcome.NOT_STARTED, 1L, null
        ));

        assertEquals(SeasonLifecycleNormalizationService.Outcome.NOT_STARTED,
                service.normalize().outcome());
        verify(closeNormalizationService, times(1)).normalize();
    }

    /**
     * 기존 트랜잭션 내부에서 호출하면 잠금·실행권을 선점하기 전에 차단합니다.
     * 실제 업무 트랜잭션의 Commit을 정상화 외부 트랜잭션과 중첩하지 않습니다.
     * 테스트가 끝나면 스레드에 남은 트랜잭션 플래그를 복원합니다.
     */
    @Test
    void outerTransactionIsRejected() {
        boolean original = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, service::normalize);
            verifyNoInteractions(closeNormalizationService, creationService,
                    initializationService, activationService, seasonRepository);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(original);
        }
    }

    /**
     * 예외가 발생하더라도 실행 중 플래그를 해제합니다.
     * 다음 정상화 시도는 기존 DB 상태를 읽어 이어서 처리할 수 있습니다.
     * 실패를 성공으로 변환하거나 결과를 숨기지 않습니다.
     */
    @Test
    void failureReleasesLocalExecutionGuard() {
        when(closeNormalizationService.normalize())
                .thenThrow(new IllegalStateException("DB error"))
                .thenReturn(closedOk());
        when(creationService.createCurrentScheduledIfMissing()).thenReturn(new SeasonCreationService.CreationResult(
                SeasonCreationService.Outcome.NOT_STARTED, 1L, null
        ));

        assertThrows(IllegalStateException.class, service::normalize);
        assertEquals(SeasonLifecycleNormalizationService.Outcome.NOT_STARTED,
                service.normalize().outcome());
        verify(closeNormalizationService, times(2)).normalize();
    }

    /**
     * 0건 종료 결과와 요청한 신규 시즌 결과를 준비합니다.
     * 실제 조회 및 Commit은 하위 서비스의 테스트에서 확인합니다.
     * 테스트에서는 첫 정상화 시각이 현재 시즌 안이라는 전제를 둡니다.
     */
    private void allowClosingAndCreation(SeasonCreationService.Outcome outcome) {
        when(closeNormalizationService.normalize()).thenReturn(closedOk());
        when(creationService.createCurrentScheduledIfMissing()).thenReturn(new SeasonCreationService.CreationResult(
                outcome, SEASON_ID, START
        ));
    }

    /**
     * 빈 시즌 종료 결과를 생성합니다.
     * 이전 시즌 종료에 실패나 다른 실행자 선점이 없음을 나타냅니다.
     * 이후 단계로 진행할 수 있는 기본 조건입니다.
     */
    private SeasonCloseNormalizationService.NormalizationResult closedOk() {
        return new SeasonCloseNormalizationService.NormalizationResult(0, 0, 0, 0, false);
    }

    /**
     * 그룹 초기화 배치 결과를 테스트에 필요한 상태·처리 수로 구성합니다.
     * 상세한 알과 점수의 생성 정확성은 E-2 테스트에서 확인합니다.
     * 여기서는 오케스트레이터의 분기와 반복 호출만 검증합니다.
     */
    private SeasonGroupInitializationService.BatchResult batch(
            SeasonGroupInitializationService.Outcome outcome, int groups
    ) {
        return new SeasonGroupInitializationService.BatchResult(outcome, groups, groups, groups, groups);
    }

    /**
     * E-3에서 반환하는 활성화 결과를 테스트에 구성합니다.
     * 정합성 SQL 검사 결과는 이 서비스가 직접 사용하지 않습니다.
     * 이후 실제 상태 조회 여부를 분기별로 확인합니다.
     */
    private SeasonActivationService.ActivationResult activation(SeasonActivationService.Outcome outcome) {
        return new SeasonActivationService.ActivationResult(outcome, null);
    }

    /**
     * DB에서 재조회한 것처럼 실제 Season 엔티티를 구성합니다.
     * JPA의 protected 생성자는 BeanUtils로 호출하고 ID와 시각만 설정합니다.
     * 마지막 isActiveAt 검증에 반드시 실제 도메인 메서드를 사용합니다.
     */
    private Season season(String status) {
        Season season = BeanUtils.instantiateClass(Season.class);
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        ReflectionTestUtils.setField(season, "status", status);
        ReflectionTestUtils.setField(season, "startAt", START);
        ReflectionTestUtils.setField(season, "endAt", END);
        return season;
    }

    /**
     * 잠금 획득 직후나 최종 확인 직전 시간을 이동시킬 수 있는 Clock입니다.
     * JDK Clock을 상속해 Thread-local 기본 시각에 의존하지 않습니다.
     * 이 테스트에서만 사용하므로 다른 테스트 클래스에 영향을 주지 않습니다.
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        /**
         * 테스트에서 관측할 다음 시각을 변경합니다.
         * 각 테스트에는 독립 인스턴스를 사용합니다.
         * 변경 시각은 이후 Clock.instant 호출에만 반영됩니다.
         */
        private void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
