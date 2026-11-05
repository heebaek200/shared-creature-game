package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.GroupSeason;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * GroupSeason 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupSeasonRepository extends JpaRepository<GroupSeason, Long> {
    @Query("""
            SELECT gs
              FROM GroupSeason gs
              JOIN FETCH gs.group g
              JOIN FETCH gs.season s
              LEFT JOIN FETCH gs.selectedSpecies
             WHERE g.id = :groupId
               AND s.id = :seasonId
            """)
    Optional<GroupSeason> findSnapshotByGroupIdAndSeasonId(
            @Param("groupId") Long groupId,
            @Param("seasonId") Long seasonId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT gs
              FROM GroupSeason gs
              JOIN FETCH gs.group g
              JOIN FETCH gs.season s
              LEFT JOIN FETCH gs.selectedSpecies
             WHERE g.id = :groupId
               AND s.id = :seasonId
            """)
    Optional<GroupSeason> findForUpdateByGroupIdAndSeasonId(
            @Param("groupId") Long groupId,
            @Param("seasonId") Long seasonId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT gs
              FROM GroupSeason gs
              JOIN FETCH gs.group
              JOIN FETCH gs.season
              LEFT JOIN FETCH gs.selectedSpecies
             WHERE gs.id = :groupSeasonId
            """)
    Optional<GroupSeason> findForUpdateById(@Param("groupSeasonId") Long groupSeasonId);

    /**
     * 그룹의 종료되지 않은 현재 시즌 참여를 쓰기 잠금으로 조회
     * - 종료된 과거 시즌은 제외
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT
                gs
            FROM
                GroupSeason AS gs
            WHERE
                gs.group.id = :groupId
            AND gs.status IN ('PREPARING', 'ACTIVE')
""")
    Optional<GroupSeason> findOpenByGroupIdForUpdate(@Param("groupId") Long groupId);
}
