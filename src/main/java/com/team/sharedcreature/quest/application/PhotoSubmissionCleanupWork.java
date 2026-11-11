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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    private final PhotoSubmissionRepository repository;
    private final PhotoSubmissionCleanupTransactionService transactions;
    private final PhotoFileStorage fileStorage;

    /**
     * 후보 조회, DB 상태 전이, 파일 저장소를 명시적으로 분리합니다.
     * DB 트랜잭션은 별도 Bean에서 열어 self-invocation 문제를 피합니다.
     * 테스트에서는 물리 파일 삭제 없이 경합과 실패 처리를 검증할 수 있습니다.
     */
    public PhotoSubmissionCleanupWork(
            PhotoSubmissionRepository repository,
            PhotoSubmissionCleanupTransactionService transactions,
            PhotoFileStorage fileStorage
    ) {
        this.repository = repository;
        this.transactions = transactions;
        this.fileStorage = fileStorage;
    }

    /**
     * 현재 시각의 기한 초과 항목을 세 종류로 분리해 최대 각 100건 처리합니다.
     * 실패 항목이 있어도 다음 사진을 계속 처리하며 실패 건수를 반환합니다.
     * 모든 파일 I/O는 현재 스레드에 DB 트랜잭션이 없을 때만 수행합니다.
     */
    @Override
    public Summary cleanup(Instant now) {
        Objects.requireNonNull(now, "now");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("사진 파일 정리는 DB 트랜잭션 밖에서만 실행해야 합니다.");
        }

        List<Long> pending = repository.findStalePendingIds(now.minus(Duration.ofHours(1)),
                PageRequest.of(0, FETCH_SIZE));
        List<Long> invalid = repository.findExpiredInvalidIds(now.minus(Duration.ofHours(24)),
                PageRequest.of(0, FETCH_SIZE));
        List<Long> valid = repository.findExpiredValidIds(now.minus(Duration.ofDays(30)),
                PageRequest.of(0, FETCH_SIZE));

        int pendingCount = 0;
        int invalidCount = 0;
        int validCount = 0;
        int failures = 0;
        boolean more = pending.size() > BATCH_SIZE || invalid.size() > BATCH_SIZE || valid.size() > BATCH_SIZE;

        // PENDING 실패 확정은 행 잠금과 별도 Commit으로 처리합니다.
        for (Long id : pending.subList(0, Math.min(pending.size(), BATCH_SIZE))) {
            try {
                if (transactions.expirePending(id, now)) pendingCount++;
            } catch (RuntimeException exception) {
                failures++;
                log.warn("사진 PENDING 만료 실패. submissionId={}", id, exception);
            }
        }
        for (Long id : invalid.subList(0, Math.min(invalid.size(), BATCH_SIZE))) {
            if (cleanupOneFile(id, RetentionKind.INVALID, now)) invalidCount++;
            else failures += isStillEligible(id, RetentionKind.INVALID, now) ? 1 : 0;
        }
        for (Long id : valid.subList(0, Math.min(valid.size(), BATCH_SIZE))) {
            if (cleanupOneFile(id, RetentionKind.VALID, now)) validCount++;
            else failures += isStillEligible(id, RetentionKind.VALID, now) ? 1 : 0;
        }

        return new Summary(pendingCount, invalidCount, validCount, failures, more);
    }

    /**
     * 삭제 가능한 행의 storageKey를 읽고 DB 트랜잭션 밖에서 파일을 삭제합니다.
     * 파일 삭제에 성공하면 별도 쓰기 트랜잭션으로 위치·키를 제거합니다.
     * 단건 예외는 삼켜 나머지 파일 정리를 계속 진행합니다.
     */
    private boolean cleanupOneFile(Long id, RetentionKind kind, Instant now) {
        try {
            Optional<FileCandidate> candidate = transactions.findEligibleFile(id, kind, now);
            if (candidate.isEmpty()) return false;

            // 삭제 실패 시 DB 이력은 그대로 남아 다음 Scheduler가 재시도할 수 있습니다.
            String key = candidate.orElseThrow().storageKey();
            if (key != null) fileStorage.delete(key);
            return transactions.confirmFileDeletion(id, kind, key, now);
        } catch (IOException | RuntimeException exception) {
            log.warn("사진 파일/위치 정리 실패. submissionId={}, kind={}", id, kind, exception);
            return false;
        }
    }

    /**
     * 처리 경쟁으로 다른 실행자가 먼저 완료했다면 실패로 집계하지 않습니다.
     * 아직 대상이면서 처리가 실패했다면 해당 실행을 FAILED로 표시합니다.
     * 재확인 자체가 실패하면 안전하게 실패 건수에 포함합니다.
     */
    private boolean isStillEligible(Long id, RetentionKind kind, Instant now) {
        try {
            return transactions.findEligibleFile(id, kind, now).isPresent();
        } catch (RuntimeException exception) {
            log.warn("사진 보존 대상 재검증 실패. submissionId={}", id, exception);
            return true;
        }
    }
}
