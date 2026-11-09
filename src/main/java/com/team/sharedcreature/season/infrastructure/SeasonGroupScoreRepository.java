package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonGroupScore;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * 한 시즌에 기록된 모든 그룹 점수를 조회합니다.
     * GroupSeason과 관계를 함께 읽어 Snapshot 정합성을 확인합니다.
     * 시즌 종료 배타 잠금을 확보한 서비스에서 사용합니다.
     */
    @Query("""
            SELECT score
              FROM SeasonGroupScore score
              JOIN FETCH score.groupSeason
             WHERE score.groupSeason.season.id = :seasonId
            """)
    List<SeasonGroupScore> findAllForSeasonSnapshot(@Param("seasonId") Long seasonId);

}
