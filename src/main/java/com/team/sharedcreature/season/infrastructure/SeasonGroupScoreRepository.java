package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonGroupScore;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 동시에 완료되는 그룹 보상에서 현재 점수 행을 배타 잠금으로 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT score FROM SeasonGroupScore score WHERE score.groupSeason.id = :groupSeasonId")
    Optional<SeasonGroupScore> findForUpdateByGroupSeasonId(
            @Param("groupSeasonId") Long groupSeasonId
    );

}
