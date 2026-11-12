package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.PhotoSubmission;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 사진 접수/검증 조회와 보존 기한 정리 조회를 제공합니다.
 * 기존 #18 접수·정리 메서드를 보존해 다른 서비스와의 호환성을 유지합니다.
 * 신규 정리 후보는 ID만 조회하고, 최종 상태 전이 시 단건 행 잠금을 사용합니다.
 */
public interface PhotoSubmissionRepository extends JpaRepository<PhotoSubmission, Long> {

    /**
     * 사용자의 동일 퀘스트 사진 제출 횟수를 반환합니다.
     * PENDING/VALID/INVALID 모두 제출 제한에 포함합니다.
     * 사진 인증 업무에서 기존 API를 그대로 사용합니다.
     */
    long countByUser_IdAndQuestAssignment_Id(Long userId, Long questAssignmentId);

    /**
     * 사용자별 일정 구간의 제출 횟수를 계산합니다.
     * 구간의 시작은 포함하고 종료는 제외합니다.
     * 사진 업로드의 분당 호출 제한 판정에 사용합니다.
     */
    long countByUser_IdAndUploadedAtGreaterThanEqualAndUploadedAtLessThan(
            Long userId, Instant from, Instant to);

    /**
     * 기존 사진 정리 코드와 호환되는 PENDING 조회입니다.
     * Lock을 사용하므로 호출자는 반드시 쓰기 트랜잭션을 보유해야 합니다.
     * 신규 #24 Scheduler는 이 쿼리 대신 ID 조회를 사용합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT submission
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'PENDING'
               AND submission.createdAt < :cutoff
             ORDER BY submission.createdAt, submission.id
            """)
    List<PhotoSubmission> findStalePendingSubmissions(
            @Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * 기존 실패 제출 보존 대상 조회를 유지합니다.
     * Lock을 사용하므로 호출자는 반드시 쓰기 트랜잭션을 보유해야 합니다.
     * 신규 #24 Scheduler는 파일 I/O 중 트랜잭션을 피하기 위해 이 메서드를 사용하지 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT submission
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'INVALID'
               AND submission.fileDeletedAt IS NULL
               AND submission.updatedAt < :cutoff
             ORDER BY submission.updatedAt, submission.id
            """)
    List<PhotoSubmission> findInvalidSubmissionsForCleanup(
            @Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * 기존 성공 제출 보존 대상 조회 메서드를 유지합니다.
     * 새 Scheduler는 시즌 ENDED 확정 조건까지 검사하는 신규 ID 조회를 사용합니다.
     * 하위 호환을 위해 시그니처를 변경하지 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT submission
              FROM PhotoSubmission submission
              JOIN submission.questAssignment assignment
              JOIN assignment.questPeriod period
              JOIN period.season season
             WHERE submission.verificationStatus = 'VALID'
               AND submission.fileDeletedAt IS NULL
               AND season.endAt < :seasonEndCutoff
             ORDER BY season.endAt, submission.id
            """)
    List<PhotoSubmission> findValidSubmissionsForCleanup(
            @Param("seasonEndCutoff") Instant seasonEndCutoff, Pageable pageable);

    /** 신규 PENDING 1시간 초과 후보를 오래된 순서로 조회합니다. */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'PENDING'
               AND submission.createdAt < :cutoff
               AND submission.cleanupNextAttemptAt IS NULL
               AND (submission.cleanupClaimToken IS NULL OR submission.cleanupClaimExpiresAt <= :now)
             ORDER BY submission.createdAt, submission.id
            """)
    List<Long> findFreshStalePendingIds(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable
    );

    /** 재시도 시각에 도달한 PENDING 후보를 조회합니다. */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'PENDING'
               AND submission.createdAt < :cutoff
               AND submission.cleanupNextAttemptAt <= :now
               AND (submission.cleanupClaimToken IS NULL OR submission.cleanupClaimExpiresAt <= :now)
             ORDER BY submission.cleanupNextAttemptAt, submission.createdAt, submission.id
            """)
    List<Long> findRetryStalePendingIds(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable
    );

    /** INVALID 확정 후 아직 시도하지 않은 사진 ID를 즉시 조회합니다. */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'INVALID'
               AND submission.fileDeletedAt IS NULL
               AND submission.cleanupNextAttemptAt IS NULL
               AND (submission.cleanupClaimToken IS NULL OR submission.cleanupClaimExpiresAt <= :now)
             ORDER BY submission.updatedAt, submission.id
            """)
    List<Long> findFreshInvalidIds(@Param("now") Instant now, Pageable pageable);

    /** 재시도 시각에 도달한 INVALID 사진 ID를 조회합니다. */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'INVALID'
               AND submission.fileDeletedAt IS NULL
               AND submission.cleanupNextAttemptAt <= :now
               AND (submission.cleanupClaimToken IS NULL OR submission.cleanupClaimExpiresAt <= :now)
             ORDER BY submission.cleanupNextAttemptAt, submission.updatedAt, submission.id
            """)
    List<Long> findRetryInvalidIds(@Param("now") Instant now, Pageable pageable);

    /** 종료 확정 후 30일이 지난 신규 VALID 사진 ID를 조회합니다. */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
              JOIN submission.questAssignment assignment
              JOIN assignment.questPeriod period
              JOIN period.season season
             WHERE submission.verificationStatus = 'VALID'
               AND submission.fileDeletedAt IS NULL
               AND season.status = 'ENDED'
               AND season.endAt <= :cutoff
               AND submission.cleanupNextAttemptAt IS NULL
               AND (submission.cleanupClaimToken IS NULL OR submission.cleanupClaimExpiresAt <= :now)
             ORDER BY season.endAt, submission.id
            """)
    List<Long> findFreshExpiredValidIds(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable
    );

    /** 재시도 시각에 도달한 VALID 사진 ID를 조회합니다. */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
              JOIN submission.questAssignment assignment
              JOIN assignment.questPeriod period
              JOIN period.season season
             WHERE submission.verificationStatus = 'VALID'
               AND submission.fileDeletedAt IS NULL
               AND season.status = 'ENDED'
               AND season.endAt <= :cutoff
               AND submission.cleanupNextAttemptAt <= :now
               AND (submission.cleanupClaimToken IS NULL OR submission.cleanupClaimExpiresAt <= :now)
             ORDER BY submission.cleanupNextAttemptAt, season.endAt, submission.id
            """)
    List<Long> findRetryExpiredValidIds(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable
    );

    /**
     * 사진 이력 한 건을 배타 잠금으로 재조회합니다.
     * PENDING 만료 확정과 파일 삭제 이후의 DB 기록에 사용합니다.
     * 없어진 이력은 빈 결과로 반환합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT submission
              FROM PhotoSubmission submission
             WHERE submission.id = :id
            """)
    Optional<PhotoSubmission> findForCleanupUpdate(@Param("id") Long id);
}
