package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * QuestTemplate 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestTemplateRepository extends JpaRepository<QuestTemplate, Long> {
}
