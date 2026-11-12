package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonGroupRankSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * SeasonGroupRankSnapshot 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonGroupRankSnapshotRepository extends JpaRepository<SeasonGroupRankSnapshot, Long> {

    /**
     * 기존에 저장된 시즌 그룹 랭킹 Snapshot을 조회합니다.
     * 이전 배치에서 저장한 순위를 확인하고 누락된 그룹만 생성합니다.
     * 한 시즌에 대한 DB 배타 잠금이 유지된 트랜잭션에서 사용합니다.
     */
    List<SeasonGroupRankSnapshot> findAllBySeasonId(Long seasonId);

    /**
     * 한 시즌의 확정된 전체 그룹 랭킹을 순위순으로 조회합니다.
     * 종료 시점에 저장한 그룹명·점수·도달 시각을 그대로 사용합니다.
     *
     * @param seasonId 조회할 시즌 ID
     * @return 확정 순위 오름차순의 그룹 랭킹 Snapshot
     */
    List<SeasonGroupRankSnapshot>
    findAllBySeasonIdOrderByRankNumberAsc(Long seasonId);

}
