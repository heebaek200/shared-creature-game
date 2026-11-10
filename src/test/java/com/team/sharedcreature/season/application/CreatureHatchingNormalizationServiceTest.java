package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.api.CreatureSelectionResponse;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 부화 후보 페이징과 개별 실패 격리를 단위 테스트로 검증합니다.
 * 실제 시즌 상태·기한과 DB 행 잠금은 다음 MySQL 통합 테스트에서 검증합니다.
 * 서비스 자체는 DB 트랜잭션을 열지 않고 기존 부화 서비스를 호출합니다.
 */
class CreatureHatchingNormalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T15:00:00Z");
    private static final Long SEASON_ID = 7L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 100);

    private final GroupSeasonRepository groupSeasons = mock(GroupSeasonRepository.class);
    private final CreatureSelectionService selection = mock(CreatureSelectionService.class);
    private final CreatureHatchingNormalizationService normalizer =
            new CreatureHatchingNormalizationService(groupSeasons, selection, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * 기한이 지난 두 그룹 시즌을 각각 부화시키고 성공 건수를 집계합니다.
     * 조회된 순서대로 메서드가 호출되는지도 확인합니다.
     * 조회 기준 시각은 주입된 Clock의 Instant여야 합니다.
     */
    @Test
    void hatchesDueGroupsInIdOrder() {
        when(groupSeasons.findDueForHatching(SEASON_ID, NOW, 0L, FIRST_PAGE))
                .thenReturn(List.of(11L, 12L));
        when(selection.hatchIfDue(anyLong())).thenReturn(Optional.of(hatchedResponse()));

        // 한 트리거 안에서는 ID 순서를 고정하고 각 행의 부화를 위임합니다.
        var result = normalizer.normalizeForActiveSeason(SEASON_ID);

        assertEquals(2, result.inspected());
        assertEquals(2, result.hatched());
        assertEquals(0, result.failed());
        assertFalse(result.moreMayRemain());
        var order = inOrder(selection);
        order.verify(selection).hatchIfDue(11L);
        order.verify(selection).hatchIfDue(12L);
    }

    /**
     * 부화 대상이 없으면 별도의 쓰기 트랜잭션을 시작하지 않습니다.
     * 이는 아직 72시간 전인 알이 저장된 경우에도 동일하게 적용됩니다.
     * 조기 반환 시 점검 실행 플래그를 정리해야 합니다.
     */
    @Test
    void skipsWhenNoGroupIsDue() {
        when(groupSeasons.findDueForHatching(SEASON_ID, NOW, 0L, FIRST_PAGE))
                .thenReturn(List.of());

        // 빈 조회 결과를 두 번 검사해도 상태가 바뀌지 않습니다.
        assertEquals(0, normalizer.normalizeForActiveSeason(SEASON_ID).inspected());
        assertEquals(0, normalizer.normalizeForActiveSeason(SEASON_ID).inspected());
        verifyNoInteractions(selection);
    }

    /**
     * 한 그룹의 부화가 실패해도 뒤 그룹은 독립 트랜잭션으로 처리합니다.
     * 실패 건수는 결과에 보존해 다음 5분 주기의 확인 대상으로 남깁니다.
     * 이미 성공한 그룹을 재부화하도록 강제하지 않습니다.
     */
    @Test
    void oneGroupFailureDoesNotBlockTheNext() {
        when(groupSeasons.findDueForHatching(SEASON_ID, NOW, 0L, FIRST_PAGE))
                .thenReturn(List.of(21L, 22L));
        when(selection.hatchIfDue(21L)).thenThrow(new IllegalStateException("species missing"));
        when(selection.hatchIfDue(22L)).thenReturn(Optional.of(hatchedResponse()));

        // 실패 결과는 로그·집계로 보존하고 다음 그룹은 처리합니다.
        var result = normalizer.normalizeForActiveSeason(SEASON_ID);

        assertEquals(2, result.inspected());
        assertEquals(1, result.hatched());
        assertEquals(1, result.failed());
        verify(selection).hatchIfDue(22L);
    }

    /**
     * 배치가 정확히 100건이면 다음 조회는 마지막 ID 다음부터 시작해야 합니다.
     * 첫 페이지의 시작을 반복 조회하면 앞쪽의 실패 그룹이 뒤 그룹을 막을 수 있습니다.
     * 인덱스 기반 페이지가 아닌 ID 기반 키셋 조회를 확인합니다.
     */
    @Test
    void advancesCursorAfterFullBatch() {
        List<Long> firstHundred = LongStream.rangeClosed(1, 100).boxed().toList();
        when(groupSeasons.findDueForHatching(SEASON_ID, NOW, 0L, FIRST_PAGE))
                .thenReturn(firstHundred);
        when(groupSeasons.findDueForHatching(SEASON_ID, NOW, 100L, FIRST_PAGE))
                .thenReturn(List.of(101L));
        when(selection.hatchIfDue(anyLong())).thenReturn(Optional.empty());

        // 첫 100개가 변화가 없어도 두 번째 페이지부터 이어서 점검합니다.
        var result = normalizer.normalizeForActiveSeason(SEASON_ID);

        assertEquals(101, result.inspected());
        assertEquals(0, result.hatched());
        verify(selection).hatchIfDue(101L);
        verify(groupSeasons).findDueForHatching(SEASON_ID, NOW, 100L, FIRST_PAGE);
    }

    /**
     * 오케스트레이터의 외부 트랜잭션은 행 잠금을 장시간 유지할 수 있습니다.
     * 개별 부화 트랜잭션이 독립적으로 완료되도록 상위 트랜잭션을 거절합니다.
     * 실제 JPA Commit 경계는 다음 DB 통합 테스트의 책임입니다.
     */
    @Test
    void rejectsOuterTransaction() {
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            // 상위 업무 트랜잭션이 활성화된 상황을 재현합니다.
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class,
                    () -> normalizer.normalizeForActiveSeason(SEASON_ID));
            verifyNoInteractions(groupSeasons, selection);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
    }

    /**
     * 같은 JVM의 두 트리거가 겹쳐도 진행 중 작업을 중복 순회하지 않습니다.
     * 첫 호출을 Mock 서비스 안에서 대기시키고 두 번째 호출의 결과를 검증합니다.
     * 대기 해제 후 첫 호출은 정상 종료되어야 합니다.
     */
    @Test
    void overlappingCallsSkipSecondRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(groupSeasons.findDueForHatching(SEASON_ID, NOW, 0L, FIRST_PAGE))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test barrier timeout");
                    }
                    return List.of(31L);
                });
        when(selection.hatchIfDue(31L)).thenReturn(Optional.of(hatchedResponse()));
        FutureTask<CreatureHatchingNormalizationService.Result> task =
                new FutureTask<>(() -> normalizer.normalizeForActiveSeason(SEASON_ID));
        Thread worker = new Thread(task, "creature-hatch-test");
        worker.start();

        try {
            // 첫 호출이 후보 조회에 들어간 뒤 두 번째는 즉시 건너뜁니다.
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var skipped = normalizer.normalizeForActiveSeason(SEASON_ID);
            assertTrue(skipped.alreadyRunning());
            assertEquals(0, skipped.inspected());
        } finally {
            release.countDown();
        }
        assertEquals(1, task.get(5, TimeUnit.SECONDS).hatched());
        worker.join(1_000);
    }

    /**
     * 부화 성공 응답은 호출 성공 여부의 식별자로만 사용합니다.
     * 실제 종 정보는 부화 서비스의 엔티티와 JPA 연동 검증에서 확인합니다.
     * 이 테스트에서는 DB와 무관한 고정값을 사용합니다.
     */
    private CreatureSelectionResponse hatchedResponse() {
        return new CreatureSelectionResponse(1L, "샘플종", "RANDOM", NOW, NOW);
    }
}
