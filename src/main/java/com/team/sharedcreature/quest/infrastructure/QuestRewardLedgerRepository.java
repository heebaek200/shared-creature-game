package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestRewardLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * QuestRewardLedger 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestRewardLedgerRepository extends JpaRepository<QuestRewardLedger, Long> {

    /**
     * 특정 완료 기록에 연결된 보상을 조회합니다.
     *
     * @param completionId 보상을 찾을 완료 기록 ID
     */
    List<QuestRewardLedger> findAllByQuestCompletionId(Long completionId);

}
