package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.PhotoSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;

/**
 * PhotoSubmission 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PhotoSubmissionRepository extends JpaRepository<PhotoSubmission, Long> {

    /**
     * 특정 사용자가 특정 배정에 제출한 이력 수를 조회합니다.
     * PENDING, VALID, INVALID 이력을 모두 세어 실패 제출도 5회 제한에 포함합니다.
     */
    long countByUser_IdAndQuestAssignment_Id(Long userId, Long questAssignmentId);

    /**
     * 지정한 시간 구간에 사용자가 제출한 이력 수를 조회합니다.
     * 구간은 [from, to)로 계산합니다. 즉 from은 포함하고 to는 제외합니다.
     */
    long countByUser_IdAndUploadedAtGreaterThanEqualAndUploadedAtLessThan(
            Long userId,
            Instant from,
            Instant to
    );

    /**
     * 지정 시각보다 오래 PENDING 상태인 제출을 오래된 순으로 조회합니다.
     * Scheduler가 1시간 이상 처리되지 않은 제출을 실패로 전환할 때 사용합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE) // 여러 앱 인스턴스의 Scheduler가 같은 제출 이력을 동시에 처리하지 않게 합니다.
    @Query("""
            SELECT submission
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'PENDING'
               AND submission.createdAt < :cutoff
             ORDER BY submission.createdAt, submission.id
            """)
    List<PhotoSubmission> findStalePendingSubmissions(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    /**
     * 실패 후 24시간이 지난 사진 제출을 오래된 순으로 조회합니다.
     * 사진 파일과 좌표를 정리하는 Scheduler가 사용합니다.
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
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    /**
     * 시즌 종료 후 30일이 지난 성공 제출을 오래된 순으로 조회합니다.
     * 현재 시각에서 30일을 뺀 값을 cutoff로 전달합니다.
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
            @Param("seasonEndCutoff") Instant seasonEndCutoff,
            Pageable pageable
    );

}
