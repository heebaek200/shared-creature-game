package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

/**
 * QuestPeriod 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestPeriodRepository extends JpaRepository<QuestPeriod, Long> {

    /**
     * 퀘스트 기간 정보를 조회하고 저장합니다.
     * 현재 시각에 진행 중인 퀘스트 기간을 시즌과 기간 유형으로 조회합니다.
     *
     * @param seasonId 현재 활성 시즌 ID
     * @param periodType 조회할 기간 유형 (일일, 주간)
     * @param now 현재 시각
     * @return 조건에 맞는 진행 중 기간. 기간이 없으면 빈 Optional
     */
    @Query("""
            SELECT p
              FROM QuestPeriod p
              JOIN FETCH p.season s
             WHERE s.id = :seasonId
               AND s.status = 'ACTIVE'
               AND p.status = 'ACTIVE'
               AND p.periodType = :periodType
               AND p.startAt <= :now
               AND p.endAt > :now
             ORDER BY p.startAt DESC
            """)
    Optional<QuestPeriod> findCurrentBySeasonAndType(
            @Param("seasonId") Long seasonId,
            @Param("periodType") String periodType,
            @Param("now") Instant now
    );
}
