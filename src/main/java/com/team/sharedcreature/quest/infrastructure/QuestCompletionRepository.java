package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestCompletion;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * QuestCompletion 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestCompletionRepository extends JpaRepository<QuestCompletion, Long> {
}
