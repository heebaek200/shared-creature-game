package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.CreatureExperienceLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CreatureExperienceLedger 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface CreatureExperienceLedgerRepository extends JpaRepository<CreatureExperienceLedger, Long> {
    Optional<CreatureExperienceLedger> findByQuestCompletionId(Long questCompletionId);

    @Query("""
            SELECT COALESCE(SUM(ledger.experienceAmount), 0)
              FROM CreatureExperienceLedger ledger
             WHERE ledger.groupCreature.id = :groupCreatureId
               AND ledger.occurredAt >= :startsAt
               AND ledger.occurredAt < :endsAt
            """)
    Long sumExperienceByCreatureAndOccurredAt(
            @Param("groupCreatureId") Long groupCreatureId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt
    );

    /** 현재 크리처의 최근 경험치 활동을 사용자와 함께 조회합니다. */
    @Query("""
            SELECT ledger
              FROM CreatureExperienceLedger ledger
              JOIN FETCH ledger.questCompletion completion
              JOIN FETCH completion.user
             WHERE ledger.groupCreature.id = :groupCreatureId
             ORDER BY ledger.occurredAt DESC, ledger.id DESC
            """)
    List<CreatureExperienceLedger> findRecentByGroupCreatureId(
            @Param("groupCreatureId") Long groupCreatureId,
            org.springframework.data.domain.Pageable pageable
    );
}
