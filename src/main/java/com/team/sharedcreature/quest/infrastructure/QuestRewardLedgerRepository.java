package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestRewardLedger;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * QuestRewardLedger 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestRewardLedgerRepository extends JpaRepository<QuestRewardLedger, Long> {
}
