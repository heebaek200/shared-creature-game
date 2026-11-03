package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.GroupCreature;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * GroupCreature 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupCreatureRepository extends JpaRepository<GroupCreature, Long> {
    @Query("""
            SELECT gc
              FROM GroupCreature gc
              LEFT JOIN FETCH gc.species
             WHERE gc.groupSeason.id = :groupSeasonId
            """)
    Optional<GroupCreature> findSnapshotByGroupSeasonId(@Param("groupSeasonId") Long groupSeasonId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT gc
              FROM GroupCreature gc
              LEFT JOIN FETCH gc.species
             WHERE gc.groupSeason.id = :groupSeasonId
            """)
    Optional<GroupCreature> findForUpdateByGroupSeasonId(@Param("groupSeasonId") Long groupSeasonId);
}
