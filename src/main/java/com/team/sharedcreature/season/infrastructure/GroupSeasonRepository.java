package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.GroupSeason;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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
     * 현재 운영 중인 시즌의 그룹 참여 기록만 조회합니다.
     * 종료되었거나 시간상 만료된 시즌의 기록은 제외합니다.
     * 조회한 그룹 시즌 행에는 배타 잠금을 적용합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT gs
          FROM GroupSeason gs
         WHERE gs.group.id = :groupId
           AND gs.season.status IN ('SCHEDULED', 'ACTIVE')
           AND gs.season.endAt > :now
           AND gs.status IN ('PREPARING', 'ACTIVE')
        """)
    Optional<GroupSeason> findOpenByGroupIdForUpdate(
            @Param("groupId") Long groupId,
            @Param("now") Instant now
    );

    /**
     * ACTIVE 시즌에서 기한이 지난 PREPARING 그룹 시즌의 ID를 조회한다.
     * 읽기 쿼리에는 배타 잠금을 사용하지 않으며 실제 부화 트랜잭션이 재검증한다.
     * afterId 오름차순 키셋으로 다음 배치를 읽어 실패 대상이 뒤 그룹을 막지 않는다.
     */
    @Query("""
        SELECT
            gs.id
        FROM
            GroupSeason AS gs
        WHERE
            gs.season.id           = :seasonId
        AND gs.season.status       = 'ACTIVE'
        AND gs.season.startAt      <= :now
        AND gs.season.endAt        > :now
        AND gs.selectionDeadlineAt <= :now
        AND gs.selectionDeadlineAt < gs.season.endAt
        AND gs.status              = 'PREPARING'
        AND gs.group.status        = 'ACTIVE'
        AND gs.id                  > :afterId
        ORDER BY
            gs.id ASC
            """)
    List<Long> findDueForHatching(
            @Param("seasonId") Long seasonId,
            @Param("now") Instant now,
            @Param("afterId") Long afterId,
            Pageable pageable
    );

    /**
     * 종료 랭킹에 포함할 전체 그룹 시즌을 조회합니다.
     * 탈퇴나 그룹 비활성화로 이미 종료된 그룹 시즌도 포함합니다.
     * 시즌 배타 잠금을 획득한 서비스에서 호출합니다.
     */
    @Query("""
            SELECT gs
              FROM GroupSeason AS gs
              JOIN FETCH gs.group
             WHERE gs.season.id = :seasonId
             ORDER BY
                   gs.group.id ASC
            """)
    List<GroupSeason> findAllForSeasonSnapshot(@Param("seasonId") Long seasonId);
}
