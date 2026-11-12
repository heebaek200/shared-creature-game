package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * QuestTemplateVersion 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestTemplateVersionRepository extends JpaRepository<QuestTemplateVersion, Long> {

    /**
     * 선택된 각 템플릿의 versionNumber가 가장 큰 버전을 조회합니다.
     * 한 템플릿에서 과거 버전은 반환하지 않으며 배정 Snapshot의 원본으로 사용합니다.
     * 빈 ID 컬렉션은 호출하지 않도록 SelectionService에서 보장합니다.
     */
    @Query("""
            SELECT version
              FROM QuestTemplateVersion version
              JOIN FETCH version.questTemplate template
             WHERE template.id IN :templateIds
               AND version.versionNumber = (
                   SELECT MAX(other.versionNumber)
                     FROM QuestTemplateVersion other
                    WHERE other.questTemplate.id = template.id
               )
            """)
    List<QuestTemplateVersion> findLatestByTemplateIds(
            @Param("templateIds") Collection<Long> templateIds
    );
}
