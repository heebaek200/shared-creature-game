package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.CreatureExperienceLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
