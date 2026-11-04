package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestCompletion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * QuestCompletion 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestCompletionRepository extends JpaRepository<QuestCompletion, Long> {

    /**
     * 지정한 사용자가 지정된 퀘스트 배정들을 완료했는지 조회합니다.
     *
     * @param userId 완료 기록을 조회할 사용자 ID
     * @param assignmentIds 완료 상태를 확인할 퀘스트 배정 ID 목록
     * @return 해당 사용자의 완료 기록 목록
     */
    List<QuestCompletion> findAllByUserIdAndQuestAssignmentIdIn(
            Long userId,
            List<Long> assignmentIds
    );

}
