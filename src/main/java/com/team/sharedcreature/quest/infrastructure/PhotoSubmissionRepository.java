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

    /**
     * PENDING 1시간 초과 후보 ID만 읽습니다.
     * 상태 전이는 별도 트랜잭션에서 행 잠금을 다시 얻은 뒤 결정합니다.
     * 정렬 순서를 고정해 배치당 처리량을 제한합니다.
     */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'PENDING'
               AND submission.createdAt < :cutoff
             ORDER BY submission.createdAt, submission.id
            """)
    List<Long> findStalePendingIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * 실패가 확정된 후 24시간을 지난 사진 ID를 조회합니다.
     * 파일 삭제 완료 기록이 있으면 후보에서 제외합니다.
     * 저장 키가 이미 없는 행도 좌표 정리를 위해 조회합니다.
     */
    @Query("""
            SELECT submission.id
              FROM PhotoSubmission submission
             WHERE submission.verificationStatus = 'INVALID'
               AND submission.fileDeletedAt IS NULL
               AND submission.updatedAt <= :cutoff
             ORDER BY submission.updatedAt, submission.id
            """)
    List<Long> findExpiredInvalidIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * 종료가 확정된 시즌의 30일 보존 기한 초과 제출만 조회합니다.
     * CLOSING 또는 ACTIVE 상태의 시즌에 남은 사진을 삭제하지 않습니다.
     * 파일 삭제 완료 이력은 다시 조회하지 않습니다.
     */
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
             ORDER BY season.endAt, submission.id
            """)
    List<Long> findExpiredValidIds(@Param("cutoff") Instant cutoff, Pageable pageable);

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
