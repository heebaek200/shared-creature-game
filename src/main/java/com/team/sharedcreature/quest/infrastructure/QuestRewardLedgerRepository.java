package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestRewardLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** 기존 완료 행에 그룹 점수 Snapshot이 없던 경우 완료 시점까지의 누적 기여 점수를 복원합니다. */
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0)
              FROM QuestRewardLedger l
             WHERE l.groupScore.groupSeason.id = :groupSeasonId
               AND l.rewardType = 'CONTRIBUTION'
               AND (
                    l.appliedAt < :completedAt
                    OR (l.appliedAt = :completedAt AND l.questCompletion.id <= :completionId)
               )
            """)
    long sumGroupContributionAtCompletion(
            @Param("groupSeasonId") Long groupSeasonId,
            @Param("completedAt") Instant completedAt,
            @Param("completionId") Long completionId
    );

}
