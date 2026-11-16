package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupTransactionService.FileCandidate;
import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupTransactionService.RetentionKind;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * #18 사진 보존 정책을 #24 Scheduler에서 재사용하는 업무 서비스입니다.
 * 파일 삭제는 어떤 DB 트랜잭션에서도 수행하지 않습니다.
 * 단건 실패는 건별로 기록하고 다음 5분 작업에서 재시도합니다.
 */
@Slf4j
@Service
public class PhotoSubmissionCleanupWork implements PhotoRetentionWork {

    private static final int BATCH_SIZE = 100;
    private static final int FETCH_SIZE = BATCH_SIZE + 1;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    private enum CleanupOutcome { SUCCEEDED, SKIPPED, FAILED }
    private record CandidateBatch(List<Long> ids, boolean moreMayRemain) { }
    private static final class FileConfirmationException extends RuntimeException {
        private FileConfirmationException(RuntimeException cause) {
            super(cause);
        }
    }

    private final PhotoSubmissionRepository repository;
    private final PhotoSubmissionCleanupTransactionService transactions;
    private final PhotoFileStorage fileStorage;
    private final PhotoCleanupLeaseHeartbeat leaseHeartbeat;
    private final Clock clock;

    /**
     * 후보 조회, DB 상태 전이, 파일 저장소를 명시적으로 분리합니다.
     * DB 트랜잭션은 별도 Bean에서 열어 self-invocation 문제를 피합니다.
     * 테스트에서는 물리 파일 삭제 없이 경합과 실패 처리를 검증할 수 있습니다.
     */
    public PhotoSubmissionCleanupWork(
            PhotoSubmissionRepository repository,
            PhotoSubmissionCleanupTransactionService transactions,
            PhotoFileStorage fileStorage,
            PhotoCleanupLeaseHeartbeat leaseHeartbeat,
            Clock clock
    ) {
        this.repository = repository;
        this.transactions = transactions;
        this.fileStorage = fileStorage;
        this.leaseHeartbeat = leaseHeartbeat;
        this.clock = clock;
    }

    /**
     * 현재 시각의 기한 초과 항목을 세 종류로 분리해 최대 각 100건 처리합니다.
     * 실패 항목이 있어도 다음 사진을 계속 처리하며 실패 건수를 반환합니다.
     * 모든 파일 I/O는 현재 스레드에 DB 트랜잭션이 없을 때만 수행합니다.
     */
    @Override
    public Summary cleanup(Instant now) {
        return cleanup(now, () -> { });
    }

    /**
     * 정리 대상을 순회하는 동안 20건마다 실행권을 갱신합니다.
     * Heartbeat 실패는 일반 사진 정리 실패처럼 삼키지 않고 즉시 전파합니다.
     * 단건 파일 I/O가 Lease보다 길어지면 갱신하지 못할 수 있으므로 상한 감시가 별도로 필요합니다.
     */
    @Override
    public Summary cleanup(Instant now, Runnable heartbeat) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(heartbeat, "heartbeat");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("사진 파일 정리는 DB 트랜잭션 밖에서만 실행해야 합니다.");
        }

        try (PhotoCleanupLeaseHeartbeat.Scope leaseScope = leaseHeartbeat.start(heartbeat, CLAIM_LEASE)) {
            return cleanupWithinLease(now, heartbeat, leaseScope);
        }
    }

    /** 고정 정책 시각과 실제 Lease 시각을 분리한 상태에서 후보를 처리합니다. */
    private Summary cleanupWithinLease(
            Instant policyNow,
            Runnable heartbeat,
            PhotoCleanupLeaseHeartbeat.Scope leaseScope
    ) {

        PageRequest page = PageRequest.of(0, FETCH_SIZE);
        CandidateBatch pending = mergeCandidates(
                repository.findFreshStalePendingIds(policyNow.minus(Duration.ofHours(1)), policyNow, page),
                repository.findRetryStalePendingIds(policyNow.minus(Duration.ofHours(1)), policyNow, page)
        );
        CandidateBatch invalid = mergeCandidates(
                repository.findFreshInvalidIds(policyNow, page),
                repository.findRetryInvalidIds(policyNow, page)
        );
        CandidateBatch valid = mergeCandidates(
                repository.findFreshExpiredValidIds(policyNow.minus(Duration.ofDays(30)), policyNow, page),
                repository.findRetryExpiredValidIds(policyNow.minus(Duration.ofDays(30)), policyNow, page)
        );

        int pendingCount = 0;
        int invalidCount = 0;
        int validCount = 0;
        int failures = 0;
        boolean more = pending.moreMayRemain() || invalid.moreMayRemain() || valid.moreMayRemain();

        // PENDING 실패 확정은 행 잠금과 별도 Commit으로 처리합니다.
        int attempted = 0;
        for (Long id : pending.ids()) {
            if (attempted++ % 20 == 0) heartbeat.run();
            try {
                if (transactions.expirePending(id, policyNow)) pendingCount++;
            } catch (RuntimeException exception) {
                failures++;
                log.warn("사진 PENDING 만료 실패. submissionId={}", id, exception);
                deferPendingAfterFailure(id);
            }
        }
        for (Long id : invalid.ids()) {
            if (attempted++ % 20 == 0) heartbeat.run();
            CleanupOutcome outcome = cleanupOneFile(
                    id, RetentionKind.INVALID, policyNow, leaseScope);
            if (outcome == CleanupOutcome.SUCCEEDED) invalidCount++;
            else if (outcome == CleanupOutcome.FAILED) failures++;
        }
        for (Long id : valid.ids()) {
            if (attempted++ % 20 == 0) heartbeat.run();
            CleanupOutcome outcome = cleanupOneFile(
                    id, RetentionKind.VALID, policyNow, leaseScope);
            if (outcome == CleanupOutcome.SUCCEEDED) validCount++;
            else if (outcome == CleanupOutcome.FAILED) failures++;
        }

        return new Summary(pendingCount, invalidCount, validCount, failures, more);
    }

    /**
     * 삭제 가능한 행의 storageKey를 읽고 DB 트랜잭션 밖에서 파일을 삭제합니다.
     * 파일 삭제에 성공하면 별도 쓰기 트랜잭션으로 위치·키를 제거합니다.
     * 단건 예외는 삼켜 나머지 파일 정리를 계속 진행합니다.
     */
    private CleanupOutcome cleanupOneFile(
            Long id,
            RetentionKind kind,
            Instant policyNow,
            PhotoCleanupLeaseHeartbeat.Scope leaseScope
    ) {
        String claimToken = UUID.randomUUID().toString();
        Optional<FileCandidate> candidate;
        Instant claimNow = clock.instant();
        try {
            candidate = transactions.claimEligibleFile(
                    id, kind, claimToken, policyNow, claimNow, claimNow.plus(CLAIM_LEASE));
        } catch (RuntimeException claimFailure) {
            return handleClaimFailure(id, kind, claimToken, policyNow, claimFailure);
        }
        if (candidate.isEmpty()) {
            return CleanupOutcome.SKIPPED;
        }

        FileCandidate claimed = candidate.orElseThrow();
        String key = claimed.storageKey();
        String activeClaimToken = claimed.claimToken();
        leaseScope.activate(id, activeClaimToken);
        try {
            try {
                if (key != null) fileStorage.delete(key);
            } catch (IOException | RuntimeException exception) {
                log.warn("사진 파일/위치 정리 실패. submissionId={}, kind={}", id, kind, exception);
                deferFileAfterFailure(id, activeClaimToken);
                return CleanupOutcome.FAILED;
            }

            try {
                // 비동기 갱신과 직렬화된 구간에서 소유권 재검증과 DB 확정을 연속 수행합니다.
                return leaseScope.completeClaim(
                        id,
                        activeClaimToken,
                        () -> {
                            try {
                                return transactions.confirmFileDeletion(
                                        id, kind, key, activeClaimToken, policyNow, clock.instant());
                            } catch (RuntimeException exception) {
                                throw new FileConfirmationException(exception);
                            }
                        })
                        ? CleanupOutcome.SUCCEEDED
                        : CleanupOutcome.SKIPPED;
            } catch (FileConfirmationException exception) {
                log.warn("사진 삭제 DB 확정 실패. submissionId={}, kind={}",
                        id, kind, exception.getCause());
                deferFileAfterFailure(id, activeClaimToken);
                return CleanupOutcome.FAILED;
            }
        } finally {
            leaseScope.deactivate(id, activeClaimToken);
        }
    }

    /** Claim 획득 예외 뒤 현재 토큰을 재검증해 안전하면 5분 재시도를 예약합니다. */
    private CleanupOutcome handleClaimFailure(
            Long id,
            RetentionKind kind,
            String attemptedClaimToken,
            Instant policyNow,
            RuntimeException claimFailure
    ) {
        Instant controlNow = clock.instant();
        try {
            var resolution = transactions.deferAfterClaimFailure(
                    id,
                    kind,
                    attemptedClaimToken,
                    policyNow,
                    controlNow,
                    controlNow.plus(RETRY_DELAY)
            );
            log.warn("사진 정리 claim 실패. submissionId={}, kind={}, resolution={}",
                    id, kind, resolution, claimFailure);
            return resolution == PhotoSubmissionCleanupTransactionService.ClaimFailureResolution.SKIPPED
                    ? CleanupOutcome.SKIPPED
                    : CleanupOutcome.FAILED;
        } catch (RuntimeException recordingFailure) {
            claimFailure.addSuppressed(recordingFailure);
            log.warn("사진 정리 claim 및 재시도 예약 실패. submissionId={}, kind={}",
                    id, kind, claimFailure);
            return CleanupOutcome.FAILED;
        }
    }

    /**
     * 신규 대상과 기한이 된 재시도 대상을 번갈아 최대 100건 선택합니다.
     * 어느 한 목록이 비면 다른 목록으로 남은 처리량을 채웁니다.
     */
    private CandidateBatch mergeCandidates(List<Long> fresh, List<Long> retry) {
        int total = Math.min(BATCH_SIZE, fresh.size() + retry.size());
        ArrayList<Long> merged = new ArrayList<>(total);
        int freshIndex = 0;
        int retryIndex = 0;
        while (merged.size() < total) {
            if (freshIndex < fresh.size()) merged.add(fresh.get(freshIndex++));
            if (merged.size() >= total) break;
            if (retryIndex < retry.size()) merged.add(retry.get(retryIndex++));
            if (freshIndex >= fresh.size() && retryIndex >= retry.size()) break;
        }
        return new CandidateBatch(List.copyOf(merged), fresh.size() + retry.size() > BATCH_SIZE);
    }

    /** PENDING 단건 실패를 5분 뒤로 보내고 기록 실패는 원래 실패와 함께 로그로 남깁니다. */
    private void deferPendingAfterFailure(Long id) {
        try {
            transactions.deferPendingCleanup(id, clock.instant().plus(RETRY_DELAY));
        } catch (RuntimeException exception) {
            log.warn("사진 PENDING 재시도 예약 실패. submissionId={}", id, exception);
        }
    }

    /** 파일 정리 실패의 claim을 해제하고 5분 뒤 재시도하도록 예약합니다. */
    private void deferFileAfterFailure(Long id, String claimToken) {
        try {
            transactions.deferFileCleanup(id, claimToken, clock.instant().plus(RETRY_DELAY));
        } catch (RuntimeException exception) {
            log.warn("사진 파일 재시도 예약 실패. submissionId={}", id, exception);
        }
    }
}
