package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 사진 정리의 데이터베이스 작업을 독립 트랜잭션으로 처리합니다.
 * 물리 파일 삭제는 이 서비스 밖에서만 실행합니다.
 * 저장소 호출 전후의 상태·보존 기한을 다시 확인해 동시 실행을 방어합니다.
 */
@Service
public class PhotoSubmissionCleanupTransactionService {

    public enum RetentionKind { INVALID, VALID }
    public enum ClaimFailureResolution { DEFERRED, SKIPPED }
    public record FileCandidate(Long id, String storageKey, String claimToken) { }

    private final PhotoSubmissionRepository repository;

    /**
     * 기존 사진 제출 Repository를 주입합니다.
     * 이 클래스는 파일 시스템에 의존하지 않습니다.
     * 각각의 공개 메서드는 독립된 DB 트랜잭션을 사용합니다.
     */
    public PhotoSubmissionCleanupTransactionService(PhotoSubmissionRepository repository) {
        this.repository = repository;
    }

    /**
     * 접수 후 1시간을 초과한 PENDING만 처리 실패로 확정합니다.
     * 행 잠금 이후 다시 시각과 상태를 확인하여 검증 작업과 경쟁하지 않습니다.
     * 변경이 없으면 false를 반환하고 기존 판정 이력은 보존합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expirePending(Long id, Instant now) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || !"PENDING".equals(submission.getVerificationStatus())
                || !submission.getCreatedAt().isBefore(now.minus(Duration.ofHours(1)))) {
            return false;
        }

        // Scheduler 미처리 실패와 EXIF 파싱 5초 타임아웃의 사유를 구별합니다.
        submission.markPendingTimedOut(now);
        repository.flush();
        return true;
    }

    /**
     * 처리하지 못한 PENDING을 다음 실행 뒤로 보내 후속 후보가 막히지 않게 합니다.
     * 원래 판정·생성 시각은 변경하지 않고 재시도 제어값만 기록합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deferPendingCleanup(Long id, Instant nextAttemptAt) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || !"PENDING".equals(submission.getVerificationStatus())) {
            return;
        }

        submission.deferCleanupUntil(nextAttemptAt);
        repository.flush();
    }

    /**
     * 파일 삭제 직전에 행을 잠그고 만료 가능한 단건 claim을 기록합니다.
     * 트랜잭션은 파일 I/O 전에 종료하며 storageKey는 확정 단계에서 다시 검사합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FileCandidate> claimEligibleFile(
            Long id,
            RetentionKind kind,
            String claimToken,
            Instant policyNow,
            Instant controlNow,
            Instant claimExpiresAt
    ) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || !eligibleForClaim(submission, kind, policyNow, controlNow)) {
            return Optional.empty();
        }

        submission.claimCleanup(claimToken, claimExpiresAt);
        repository.flush();
        return Optional.of(new FileCandidate(submission.getId(), submission.getStorageKey(), claimToken));
    }

    /**
     * 물리 파일 삭제를 마친 이력의 저장 키와 좌표를 비웁니다.
     * 배타 잠금 이후 현재 상태·기한·storageKey를 재확인합니다.
     * DB Commit 실패 시 파일은 이미 없을 수 있으나 다음 실행이 안전하게 재시도합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean confirmFileDeletion(
            Long id,
            RetentionKind kind,
            String deletedStorageKey,
            String claimToken,
            Instant policyNow,
            Instant completedAt
    ) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || !eligibleByPolicy(submission, kind, policyNow)
                || !Objects.equals(submission.getCleanupClaimToken(), claimToken)
                || !Objects.equals(submission.getStorageKey(), deletedStorageKey)) {
            return false;
        }

        // 파일 삭제 후에만 위치·키를 제거하고 최소 판정 이력은 남깁니다.
        submission.markPhotoAndLocationDataDeleted(completedAt);
        repository.flush();
        return true;
    }

    /**
     * 파일 I/O 중 현재 토큰을 보유한 단건 claim의 만료 시각을 연장합니다.
     * 재선점 또는 정리 완료 후에는 false를 반환해 이전 실행의 확정을 중단시킵니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewFileCleanupClaim(
            Long id,
            String claimToken,
            Instant controlNow,
            Instant claimExpiresAt
    ) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || submission.getFileDeletedAt() != null
                || !Objects.equals(submission.getCleanupClaimToken(), claimToken)
                || submission.getCleanupClaimExpiresAt() == null
                || !submission.getCleanupClaimExpiresAt().isAfter(controlNow)) {
            return false;
        }

        submission.claimCleanup(claimToken, claimExpiresAt);
        repository.flush();
        return true;
    }

    /**
     * Claim 획득 예외 뒤 현재 행 상태를 다시 잠그고 안전하면 5분 재시도를 예약합니다.
     * 다른 실행자의 유효한 Claim은 보존하고 정상 경합으로 건너뜁니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimFailureResolution deferAfterClaimFailure(
            Long id,
            RetentionKind kind,
            String attemptedClaimToken,
            Instant policyNow,
            Instant controlNow,
            Instant nextAttemptAt
    ) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || !eligibleByPolicy(submission, kind, policyNow)) {
            return ClaimFailureResolution.SKIPPED;
        }

        String currentToken = submission.getCleanupClaimToken();
        boolean otherActiveClaim = currentToken != null
                && !Objects.equals(currentToken, attemptedClaimToken)
                && submission.getCleanupClaimExpiresAt() != null
                && submission.getCleanupClaimExpiresAt().isAfter(controlNow);
        if (otherActiveClaim) {
            return ClaimFailureResolution.SKIPPED;
        }

        submission.deferCleanupUntil(nextAttemptAt);
        repository.flush();
        return ClaimFailureResolution.DEFERRED;
    }

    /**
     * 파일 삭제 실패를 기록하고 claim을 해제해 지정 시각 이후 재시도합니다.
     * 다른 실행이 claim을 재선점했다면 그 실행의 제어값을 변경하지 않습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deferFileCleanup(Long id, String claimToken, Instant nextAttemptAt) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || submission.getFileDeletedAt() != null
                || !Objects.equals(submission.getCleanupClaimToken(), claimToken)) {
            return;
        }

        submission.deferCleanupUntil(nextAttemptAt);
        repository.flush();
    }

    /** 파일 정리 정책, 재시도 시각과 기존 claim 만료 여부를 함께 검사합니다. */
    private boolean eligibleForClaim(
            PhotoSubmission submission,
            RetentionKind kind,
            Instant policyNow,
            Instant controlNow
    ) {
        if (!eligibleByPolicy(submission, kind, policyNow)) {
            return false;
        }
        if (submission.getCleanupNextAttemptAt() != null
                && submission.getCleanupNextAttemptAt().isAfter(controlNow)) {
            return false;
        }
        return submission.getCleanupClaimToken() == null
                || submission.getCleanupClaimExpiresAt() != null
                && !submission.getCleanupClaimExpiresAt().isAfter(controlNow);
    }

    /**
     * 제출 상태와 확정 보존 정책을 검사합니다.
     * INVALID는 다음 정리 실행부터, VALID는 시즌 ENDED 후 30일부터 대상입니다.
     */
    private boolean eligibleByPolicy(PhotoSubmission submission, RetentionKind kind, Instant now) {
        if (submission.getFileDeletedAt() != null) {
            return false;
        }
        if (kind == RetentionKind.INVALID) {
            return "INVALID".equals(submission.getVerificationStatus());
        }

        // 종료 처리 중인 시즌의 파일은 최종 Snapshot 확정 전까지 삭제하지 않습니다.
        var season = submission.getQuestAssignment().getQuestPeriod().getSeason();
        return "VALID".equals(submission.getVerificationStatus())
                && "ENDED".equals(season.getStatus())
                && !season.getEndAt().isAfter(now.minus(Duration.ofDays(30)));
    }
}
