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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private MutableClock clock;

    /**
     * 모든 후보 목록을 빈 목록으로 준비합니다.
     * 원하는 상황의 후보만 테스트별로 덮어씁니다.
     * 불필요한 Mock 설정으로 테스트가 실패하지 않도록 lenient를 사용합니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        PhotoCleanupLeaseHeartbeat leaseHeartbeat = new PhotoCleanupLeaseHeartbeat(
                transactions, clock, Duration.ofMinutes(1));
        work = new PhotoSubmissionCleanupWork(
                repository, transactions, storage, leaseHeartbeat, clock);
        lenient().when(transactions.renewFileCleanupClaim(
                        anyLong(), anyString(), any(), any()))
                .thenReturn(true);
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
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(11L, "invalid-key.jpg", "claim")));
        when(transactions.confirmFileDeletion(eq(11L), eq(RetentionKind.INVALID),
                eq("invalid-key.jpg"), anyString(), eq(NOW), eq(NOW)))
                .thenReturn(true);

        var result = work.cleanup(NOW);
        assertEquals(1, result.invalidFilesDeleted());
        verify(storage).delete("invalid-key.jpg");
        verify(transactions).confirmFileDeletion(eq(11L), eq(RetentionKind.INVALID),
                eq("invalid-key.jpg"), anyString(), eq(NOW), eq(NOW));
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
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(21L, "valid-key.jpg", "claim")));
        when(transactions.confirmFileDeletion(eq(21L), eq(RetentionKind.VALID),
                eq("valid-key.jpg"), anyString(), eq(NOW), eq(NOW)))
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
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(17L, "broken-key.jpg", "claim")));
        doThrow(new IOException("locked file")).when(storage).delete("broken-key.jpg");

        var result = work.cleanup(NOW);
        assertEquals(1, result.failedCount());
        verify(transactions, never()).confirmFileDeletion(
                anyLong(), any(), any(), anyString(), any(), any());
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
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
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

    /** Claim 획득 예외는 실제 실패 시각부터 5분 뒤 재시도로 전환합니다. */
    @Test
    void claimFailureSchedulesRetryFromActualTime() {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101))).thenReturn(List.of(41L));
        RuntimeException claimFailure = new IllegalStateException("claim failure");
        when(transactions.claimEligibleFile(eq(41L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenThrow(claimFailure);
        when(transactions.deferAfterClaimFailure(eq(41L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5)))))
                .thenReturn(PhotoSubmissionCleanupTransactionService.ClaimFailureResolution.DEFERRED);

        var result = work.cleanup(NOW);

        assertEquals(1, result.failedCount());
        verify(transactions).deferAfterClaimFailure(eq(41L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5))));
    }

    /** 재시도 예약도 실패하면 원래 Claim 오류에 후속 오류를 함께 보존합니다. */
    @Test
    void retryReservationFailureDoesNotHideClaimFailure() {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101))).thenReturn(List.of(42L));
        RuntimeException claimFailure = new IllegalStateException("claim failure");
        RuntimeException recordingFailure = new IllegalStateException("recording failure");
        when(transactions.claimEligibleFile(eq(42L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenThrow(claimFailure);
        when(transactions.deferAfterClaimFailure(eq(42L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5)))))
                .thenThrow(recordingFailure);

        var result = work.cleanup(NOW);

        assertEquals(1, result.failedCount());
        assertArrayEquals(new Throwable[]{recordingFailure}, claimFailure.getSuppressed());
    }

    /** 다른 실행자의 정상 Claim이 확인되면 기술 실패 건수로 중복 집계하지 않습니다. */
    @Test
    void claimFailureResolvedByOtherOwnerIsSkipped() {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101))).thenReturn(List.of(43L));
        when(transactions.claimEligibleFile(eq(43L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenThrow(new IllegalStateException("claim race"));
        when(transactions.deferAfterClaimFailure(eq(43L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5)))))
                .thenReturn(PhotoSubmissionCleanupTransactionService.ClaimFailureResolution.SKIPPED);

        assertEquals(0, work.cleanup(NOW).failedCount());
    }

    /** 작업 시작 11분 뒤 완료된 파일도 실제 시각 기준으로 Claim을 갱신하고 확정합니다. */
    @Test
    void longFileIoRenewsClaimFromActualTimeBeforeConfirmation() throws Exception {
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101))).thenReturn(List.of(44L));
        when(transactions.claimEligibleFile(eq(44L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(44L, "long.jpg", "claim")));
        doAnswer(invocation -> {
            clock.set(NOW.plus(Duration.ofMinutes(11)));
            return null;
        }).when(storage).delete("long.jpg");
        when(transactions.confirmFileDeletion(
                44L, RetentionKind.INVALID, "long.jpg", "claim",
                NOW, NOW.plus(Duration.ofMinutes(11)))).thenReturn(true);

        var result = work.cleanup(NOW);

        assertEquals(1, result.invalidFilesDeleted());
        verify(transactions).renewFileCleanupClaim(
                44L, "claim", NOW.plus(Duration.ofMinutes(11)),
                NOW.plus(Duration.ofMinutes(21)));
        verify(transactions).confirmFileDeletion(
                44L, RetentionKind.INVALID, "long.jpg", "claim",
                NOW, NOW.plus(Duration.ofMinutes(11)));
    }

    /** 진행 중 Heartbeat와 삭제 완료가 겹쳐도 정상 항목을 성공으로 집계합니다. */
    @Test
    void inFlightHeartbeatDoesNotTurnCompletedDeletionIntoFailure() throws Exception {
        CountDownLatch renewalEntered = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        CountDownLatch confirmationEntered = new CountDownLatch(1);
        AtomicInteger renewals = new AtomicInteger();
        PhotoCleanupLeaseHeartbeat concurrentHeartbeat = new PhotoCleanupLeaseHeartbeat(
                transactions, clock, Duration.ofMillis(10));
        PhotoSubmissionCleanupWork concurrentWork = new PhotoSubmissionCleanupWork(
                repository, transactions, storage, concurrentHeartbeat, clock);
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101))).thenReturn(List.of(47L));
        when(transactions.claimEligibleFile(eq(47L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(47L, "racing.jpg", "claim")));
        when(transactions.renewFileCleanupClaim(anyLong(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    if (renewals.incrementAndGet() == 1) {
                        renewalEntered.countDown();
                        if (!releaseRenewal.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("renewal release timeout");
                        }
                    }
                    return true;
                });
        doAnswer(invocation -> {
            if (!renewalEntered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("heartbeat start timeout");
            }
            return null;
        }).when(storage).delete("racing.jpg");
        when(transactions.confirmFileDeletion(
                47L, RetentionKind.INVALID, "racing.jpg", "claim", NOW, NOW))
                .thenAnswer(invocation -> {
                    confirmationEntered.countDown();
                    return true;
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PhotoRetentionWork.Summary> result = pool.submit(() -> concurrentWork.cleanup(NOW));
            assertTrue(renewalEntered.await(5, TimeUnit.SECONDS));
            assertFalse(confirmationEntered.await(100, TimeUnit.MILLISECONDS));

            releaseRenewal.countDown();
            PhotoRetentionWork.Summary summary = result.get(5, TimeUnit.SECONDS);

            assertEquals(1, summary.invalidFilesDeleted());
            assertEquals(0, summary.failedCount());
            verify(storage).delete("racing.jpg");
            verify(transactions).confirmFileDeletion(
                    47L, RetentionKind.INVALID, "racing.jpg", "claim", NOW, NOW);
        } finally {
            releaseRenewal.countDown();
            pool.shutdownNow();
        }
    }

    /** 작업 시작 10분 뒤의 다음 사진은 고정 정책 시각이 아닌 실제 시각으로 Claim을 생성합니다. */
    @Test
    void laterCandidateUsesActualTimeForNewClaim() throws Exception {
        Instant later = NOW.plus(Duration.ofMinutes(11));
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(List.of(45L, 46L));
        when(transactions.claimEligibleFile(eq(45L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(45L, "first.jpg", "first-claim")));
        doAnswer(invocation -> {
            clock.set(later);
            return null;
        }).when(storage).delete("first.jpg");
        when(transactions.confirmFileDeletion(
                45L, RetentionKind.INVALID, "first.jpg", "first-claim", NOW, later))
                .thenReturn(true);
        when(transactions.claimEligibleFile(eq(46L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(later), eq(later.plus(Duration.ofMinutes(10)))))
                .thenReturn(Optional.of(new FileCandidate(46L, "second.jpg", "second-claim")));
        when(transactions.confirmFileDeletion(
                46L, RetentionKind.INVALID, "second.jpg", "second-claim", NOW, later))
                .thenReturn(true);

        var result = work.cleanup(NOW);

        assertEquals(2, result.invalidFilesDeleted());
        verify(transactions).claimEligibleFile(eq(46L), eq(RetentionKind.INVALID), anyString(),
                eq(NOW), eq(later), eq(later.plus(Duration.ofMinutes(10))));
    }

    /** 반복 Claim 오류 100건이 신규 사진 200건의 처리 기회를 막지 않습니다. */
    @Test
    void repeatedClaimFailuresDoNotStarveFollowingTwoHundredCandidates() {
        List<Long> failing = LongStream.rangeClosed(1, 100).boxed().toList();
        when(repository.findFreshInvalidIds(NOW, PageRequest.of(0, 101)))
                .thenReturn(LongStream.rangeClosed(1, 101).boxed().toList());
        when(repository.findRetryInvalidIds(NOW, PageRequest.of(0, 101))).thenReturn(List.of());
        for (int run = 1; run < 5; run++) {
            Instant runAt = NOW.plus(Duration.ofMinutes(5L * run));
            long freshStart = 51L + 50L * run;
            when(repository.findFreshInvalidIds(runAt, PageRequest.of(0, 101)))
                    .thenReturn(LongStream.rangeClosed(freshStart, Math.min(300, freshStart + 100))
                            .boxed().toList());
            when(repository.findRetryInvalidIds(runAt, PageRequest.of(0, 101))).thenReturn(failing);
        }
        when(transactions.claimEligibleFile(anyLong(), eq(RetentionKind.INVALID), anyString(),
                any(), any(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id <= 100) throw new IllegalStateException("persistent claim failure");
            String token = invocation.getArgument(2);
            return Optional.of(new FileCandidate(id, id + ".jpg", token));
        });
        when(transactions.deferAfterClaimFailure(anyLong(), eq(RetentionKind.INVALID), anyString(),
                any(), any(), any()))
                .thenReturn(PhotoSubmissionCleanupTransactionService.ClaimFailureResolution.DEFERRED);
        Set<Long> confirmed = new HashSet<>();
        when(transactions.confirmFileDeletion(anyLong(), eq(RetentionKind.INVALID), anyString(),
                anyString(), any(), any())).thenAnswer(invocation -> confirmed.add(invocation.getArgument(0)));

        for (int run = 0; run < 5; run++) {
            Instant runAt = NOW.plus(Duration.ofMinutes(5L * run));
            clock.set(runAt);
            work.cleanup(runAt);
        }

        assertEquals(200, confirmed.size());
        assertTrue(confirmed.containsAll(LongStream.rangeClosed(101, 300).boxed().toList()));
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

    /** 테스트에서 실제 대기 없이 Claim 제어 시각을 이동합니다. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        private void set(Instant instant) {
            current.set(instant);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }

}
