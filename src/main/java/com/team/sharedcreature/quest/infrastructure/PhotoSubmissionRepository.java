package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.PhotoSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

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

}
