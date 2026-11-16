package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonMemberRankSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * SeasonMemberRankSnapshot 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonMemberRankSnapshotRepository extends JpaRepository<SeasonMemberRankSnapshot, Long> {

    /**
     * 한 시즌에 이미 저장된 개인 종료 Snapshot 전체를 조회합니다.
     * 그룹 시즌·사용자별 중복과 이미 확정된 순위를 검증합니다.
     * 이전 배치에서 확정한 값을 재실행해도 갱신하지 않습니다.
     */
    List<SeasonMemberRankSnapshot> findAllBySeasonId(Long seasonId);

    /**
     * 한 그룹 시즌의 확정된 개인 랭킹을 순위순으로 조회합니다.
     * 종료 시점에 저장한 닉네임·점수·참여 상태를 그대로 사용합니다.
     *
     * @param groupSeasonId 조회할 그룹 시즌 ID
     * @return 확정 순위 오름차순의 개인 랭킹 Snapshot
     */
    List<SeasonMemberRankSnapshot>
    findAllByGroupSeasonIdOrderByRankNumberAsc(Long groupSeasonId);
}
