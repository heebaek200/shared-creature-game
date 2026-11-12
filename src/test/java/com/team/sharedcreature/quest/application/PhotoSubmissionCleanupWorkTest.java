package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupTransactionService.FileCandidate;
import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupTransactionService.RetentionKind;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 실제 파일을 삭제하지 않고 정리 작업의 처리 순서와 실패 복구 정책을 검증합니다.
 * #18의 저장소는 Mock으로 대체하되 외부 DB 트랜잭션이 없는 경계를 테스트합니다.
 * 반쪽 상태가 생겼을 때 재실행해야 할 항목이 실패 건수에 반영되는지 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class PhotoSubmissionCleanupWorkTest {
    private static final Instant NOW = Instant.parse("2026-11-10T09:07:45Z");

    @Mock private PhotoSubmissionRepository repository;
    @Mock private PhotoSubmissionCleanupTransactionService transactions;
    @Mock private PhotoFileStorage storage;
    private PhotoSubmissionCleanupWork work;

    /**
     * 모든 후보 목록을 빈 목록으로 준비합니다.
     * 원하는 상황의 후보만 테스트별로 덮어씁니다.
     * 불필요한 Mock 설정으로 테스트가 실패하지 않도록 lenient를 사용합니다.
     */
    @BeforeEach
    void setUp() {
        work = new PhotoSubmissionCleanupWork(repository, transactions, storage);
        lenient().when(repository.findFreshStalePendingIds(
                        NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of());
        lenient().when(repository.findRetryStalePendingIds(
                        NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of());
        lenient().when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of());
        lenient().when(repository.findRetryInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of());
        lenient().when(repository.findFreshExpiredValidIds(
                        NOW.minus(Duration.ofDays(30)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of());
        lenient().when(repository.findRetryExpiredValidIds(
                        NOW.minus(Duration.ofDays(30)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of());
    }

    /**
     * 현재 시각 기준으로 1시간 지난 PENDING의 ID를 조회합니다.
     * 파일은 지우지 않고 행 잠금 기반 판정만 위임합니다.
     * 성공이 확인된 건만 처리 건수에 포함합니다.
     */
    @Test
    void expiresStalePendingWithoutDeletingFile() {
        when(repository.findFreshStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(7L));
        when(transactions.expirePending(7L, NOW)).thenReturn(true);

        var result = work.cleanup(NOW);
        assertEquals(1, result.pendingInvalidated());
        assertEquals(0, result.failedCount());
        verifyNoInteractions(storage);
    }

    /**
     * 실패 사진을 지운 다음에만 위치와 저장 키의 삭제 확정을 기록합니다.
     * DB와 파일 삭제의 담당 Bean이 서로 다릅니다.
     * 삭제 성공은 성공 건수에 정확히 한 번만 반영됩니다.
     */
    @Test
    void deletesInvalidFileThenConfirmsDbCleanup() throws Exception {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(11L));
        when(transactions.claimEligibleFile(eq(11L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(11L, "invalid-key.jpg", "claim")));
        when(transactions.confirmFileDeletion(eq(11L), eq(RetentionKind.INVALID),
                eq("invalid-key.jpg"), anyString(), eq(NOW)))
                .thenReturn(true);

        var result = work.cleanup(NOW);
        assertEquals(1, result.invalidFilesDeleted());
        verify(storage).delete("invalid-key.jpg");
        verify(transactions).confirmFileDeletion(eq(11L), eq(RetentionKind.INVALID),
                eq("invalid-key.jpg"), anyString(), eq(NOW));
    }

    /**
     * 성공 사진의 삭제도 #18 파일 저장소 구현을 그대로 재사용합니다.
     * 보존 대상 선정은 Repository가 실제 시즌 상태와 종료 시각으로 필터링합니다.
     * 최종 커밋 전에 별도 트랜잭션이 재검증합니다.
     */
    @Test
    void deletesValidFileAfterEligibilityCheck() throws Exception {
        when(repository.findFreshExpiredValidIds(
                NOW.minus(Duration.ofDays(30)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(21L));
        when(transactions.claimEligibleFile(eq(21L), eq(RetentionKind.VALID), anyString(),
                eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(21L, "valid-key.jpg", "claim")));
        when(transactions.confirmFileDeletion(eq(21L), eq(RetentionKind.VALID),
                eq("valid-key.jpg"), anyString(), eq(NOW)))
                .thenReturn(true);

        var result = work.cleanup(NOW);
        assertEquals(1, result.validFilesDeleted());
        verify(storage).delete("valid-key.jpg");
    }

    /**
     * 파일 삭제가 실패해도 DB의 저장 키·좌표는 그대로 둡니다.
     * 다음 5분 Scheduler에서 같은 행을 다시 검사할 수 있어야 합니다.
     * 단건 실패는 전체 처리 결과의 failedCount에 포함됩니다.
     */
    @Test
    void ioFailureKeepsDatabasePendingForRetry() throws Exception {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(17L));
        when(transactions.claimEligibleFile(eq(17L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(17L, "broken-key.jpg", "claim")));
        doThrow(new IOException("locked file")).when(storage).delete("broken-key.jpg");

        var result = work.cleanup(NOW);
        assertEquals(1, result.failedCount());
        verify(transactions, never()).confirmFileDeletion(anyLong(), any(), any(), anyString(), any());
        verify(transactions).deferFileCleanup(eq(17L), anyString(), eq(NOW.plus(Duration.ofMinutes(5))));
    }

    /**
     * 후보가 다른 실행자에 의해 이미 정리됐다면 성공과 실패로 중복 집계하지 않습니다.
     * 재실행되는 Scheduler는 저장된 파일을 다시 지우지 않습니다.
     * 이미 완료된 판정 이력을 임의로 변경하지 않습니다.
     */
    @Test
    void alreadyCompletedCandidateIsSkipped() {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(31L));
        when(transactions.claimEligibleFile(eq(31L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.empty());

        var result = work.cleanup(NOW);
        assertEquals(0, result.invalidFilesDeleted());
        assertEquals(0, result.failedCount());
        verifyNoInteractions(storage);
    }

    /**
     * 한 회차에서 종류별 최대 100건만 처리하고 다음 구간 작업 여부를 기록합니다.
     * 101번째 대상은 이 실행에서 전환하지 않습니다.
     * 작업량 상한은 Scheduler 실행권을 오래 붙잡지 않기 위한 방어입니다.
     */
    @Test
    void limitsEachCategoryToOneHundred() {
        List<Long> ids = LongStream.rangeClosed(1, 101).boxed().toList();
        when(repository.findFreshStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101)))
                .thenReturn(ids);

        var result = work.cleanup(NOW);
        assertTrue(result.moreMayRemain());
        verify(transactions, times(100)).expirePending(anyLong(), eq(NOW));
        verify(transactions, never()).expirePending(101L, NOW);
    }

    /**
     * 신규 후보와 재시도 후보가 모두 많아도 양쪽에 처리 기회를 배정합니다.
     * 영구 실패 재시도만 또는 신규 항목만 앞쪽 100건을 독점하지 않습니다.
     */
    @Test
    void interleavesFreshAndRetryCandidatesWithinBatchLimit() {
        List<Long> fresh = LongStream.rangeClosed(1, 101).boxed().toList();
        List<Long> retry = LongStream.rangeClosed(1_001, 1_101).boxed().toList();
        when(repository.findFreshStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101))).thenReturn(fresh);
        when(repository.findRetryStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101))).thenReturn(retry);

        var result = work.cleanup(NOW);

        assertTrue(result.moreMayRemain());
        verify(transactions, times(100)).expirePending(anyLong(), eq(NOW));
        verify(transactions).expirePending(50L, NOW);
        verify(transactions).expirePending(1_050L, NOW);
        verify(transactions, never()).expirePending(51L, NOW);
        verify(transactions, never()).expirePending(1_051L, NOW);
    }

    /** 실패한 PENDING은 5분 뒤로 예약해 다음 후보가 후속 실행에서 먼저 처리되게 합니다. */
    @Test
    void failedPendingIsDeferredForFiveMinutes() {
        when(repository.findFreshStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101))).thenReturn(List.of(9L));
        when(transactions.expirePending(9L, NOW)).thenThrow(new IllegalStateException("row failure"));

        var result = work.cleanup(NOW);

        assertEquals(1, result.failedCount());
        verify(transactions).deferPendingCleanup(9L, NOW.plus(Duration.ofMinutes(5)));
    }

    /**
     * 외부 트랜잭션 안에서 파일 삭제를 시작하면 안 됩니다.
     * 데이터베이스 커넥션을 파일 I/O 동안 점유하지 않도록 보장합니다.
     * 스레드 테스트 플래그는 검사 종료 후 원래 값으로 복원합니다.
     */
    @Test
    void rejectsExistingTransaction() {
        boolean original = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, () -> work.cleanup(NOW));
            verifyNoInteractions(transactions, storage);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(original);
        }
    }
    /**
     * 총 21개를 처리할 때 첫 건과 21번째 처리 직전에 Heartbeat 합니다.
     * 작업이 오래 걸려도 각 항목 사이에서 Lease 갱신 기회가 생깁니다.
     * 나머지 업로드/DB 로직은 Mockito로 대체합니다.
     */
    @Test
    void heartbeatEveryTwentyAttempts() {
        List<Long> ids = LongStream.rangeClosed(1, 21).boxed().toList();
        when(repository.findFreshStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101)))
                .thenReturn(ids);
        java.util.concurrent.atomic.AtomicInteger heartbeats = new java.util.concurrent.atomic.AtomicInteger();

        var result = work.cleanup(NOW, heartbeats::incrementAndGet);
        assertEquals(2, heartbeats.get());
        assertEquals(0, result.failedCount());
        verify(transactions, times(21)).expirePending(anyLong(), eq(NOW));
    }

    /**
     * 실행권을 상실했다면 처리 중 오류를 단건 실패처럼 삼키면 안 됩니다.
     * Heartbeat 예외는 SchedulerJobRunner까지 전달해야 합니다.
     * 작업 중단 시 DB/파일을 더 이상 수정하지 않아야 합니다.
     */
    @Test
    void heartbeatFailureAbortsWork() {
        when(repository.findFreshStalePendingIds(
                NOW.minus(Duration.ofHours(1)), NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(4L));
        assertThrows(IllegalStateException.class, () -> work.cleanup(NOW, () -> {
            throw new IllegalStateException("lease lost");
        }));
        verifyNoInteractions(transactions, storage);
    }

}
