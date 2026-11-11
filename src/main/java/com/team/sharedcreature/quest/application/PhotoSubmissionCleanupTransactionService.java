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
    public record FileCandidate(Long id, String storageKey) { }

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
     * 파일 삭제 작업 직전에 이력의 보존 기한을 읽습니다.
     * 이 트랜잭션은 반환 전에 종료되며 파일 I/O 중 DB 잠금을 보유하지 않습니다.
     * 반환된 storageKey가 실제 삭제 후에도 같은지 별도 확정 트랜잭션에서 검증합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<FileCandidate> findEligibleFile(Long id, RetentionKind kind, Instant now) {
        return repository.findById(id)
                .filter(submission -> eligible(submission, kind, now))
                .map(submission -> new FileCandidate(submission.getId(), submission.getStorageKey()));
    }

    /**
     * 물리 파일 삭제를 마친 이력의 저장 키와 좌표를 비웁니다.
     * 배타 잠금 이후 현재 상태·기한·storageKey를 재확인합니다.
     * DB Commit 실패 시 파일은 이미 없을 수 있으나 다음 실행이 안전하게 재시도합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean confirmFileDeletion(Long id, RetentionKind kind, String deletedStorageKey, Instant now) {
        PhotoSubmission submission = repository.findForCleanupUpdate(id).orElse(null);
        if (submission == null || !eligible(submission, kind, now)
                || !Objects.equals(submission.getStorageKey(), deletedStorageKey)) {
            return false;
        }

        // 파일 삭제 후에만 위치·키를 제거하고 최소 판정 이력은 남깁니다.
        submission.markPhotoAndLocationDataDeleted(now);
        repository.flush();
        return true;
    }

    /**
     * 제출 상태와 보존 정책을 한곳에서 검증합니다.
     * VALID는 논리 종료 시각뿐 아니라 실제 시즌 ENDED 상태를 요구합니다.
     * INVALID는 실패가 확정된 updatedAt부터 24시간 이후에만 삭제합니다.
     */
    private boolean eligible(PhotoSubmission submission, RetentionKind kind, Instant now) {
        if (submission.getFileDeletedAt() != null) {
            return false;
        }
        if (kind == RetentionKind.INVALID) {
            return "INVALID".equals(submission.getVerificationStatus())
                    && !submission.getUpdatedAt().isAfter(now.minus(Duration.ofHours(24)));
        }

        // 종료 처리 중인 시즌의 파일은 최종 Snapshot 확정 전까지 삭제하지 않습니다.
        var season = submission.getQuestAssignment().getQuestPeriod().getSeason();
        return "VALID".equals(submission.getVerificationStatus())
                && "ENDED".equals(season.getStatus())
                && !season.getEndAt().isAfter(now.minus(Duration.ofDays(30)));
    }
}
