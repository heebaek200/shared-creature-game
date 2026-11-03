package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * QuestTemplateVersion 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestTemplateVersionRepository extends JpaRepository<QuestTemplateVersion, Long> {
}
