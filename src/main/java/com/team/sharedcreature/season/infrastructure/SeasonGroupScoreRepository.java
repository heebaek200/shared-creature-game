package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonGroupScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * SeasonGroupScore 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonGroupScoreRepository extends JpaRepository<SeasonGroupScore, Long> {

    /**
     * 그룹 시즌의 점수 행을 조회합니다.
     *
     * @param groupSeasonId 점수를 조회할 그룹 시즌 ID
     * @return 해당 그룹 시즌의 점수
     */
    Optional<SeasonGroupScore> findByGroupSeasonId(Long groupSeasonId);

}
