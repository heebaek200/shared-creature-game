package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * QuestTemplate 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestTemplateRepository extends JpaRepository<QuestTemplate, Long> {

    /**
     * 해당 기간에 사용할 ACTIVE 퀘스트 템플릿을 회전 순서대로 조회합니다.
     * INACTIVE·EXPERIMENTAL 템플릿은 선정 대상에서 제외합니다.
     * 난이도별 최소 개수와 중복 검증은 SelectionService에서 수행합니다.
     */
    @Query("""
            SELECT t
              FROM QuestTemplate t
             WHERE t.status = 'ACTIVE'
               AND t.periodType = :periodType
             ORDER BY t.difficulty ASC, t.rotationOrder ASC, t.id ASC
            """)
    List<QuestTemplate> findActiveByPeriodType(@Param("periodType") String periodType);
}
