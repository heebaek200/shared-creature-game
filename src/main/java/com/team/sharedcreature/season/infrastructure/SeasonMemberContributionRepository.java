package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * SeasonMemberContribution 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonMemberContributionRepository extends JpaRepository<SeasonMemberContribution, Long> {

    /**
     * 그룹 시즌에서 특정 사용자의 기여도 행을 조회합니다.
     *
     * @param groupSeasonId 그룹 시즌 ID
     * @param userId 사용자 ID
     * @return 해당 사용자의 시즌 기여도
     */
    Optional<SeasonMemberContribution> findByGroupSeasonIdAndUserId(
            Long groupSeasonId,
            Long userId
    );

    /** 현재 그룹 시즌의 기여도와 사용자를 실시간 순위 계산용으로 조회합니다. */
    @Query("""
            SELECT contribution
              FROM SeasonMemberContribution contribution
              JOIN FETCH contribution.user
             WHERE contribution.groupSeason.id = :groupSeasonId
            """)
    List<SeasonMemberContribution> findAllByGroupSeasonIdWithUser(
            @Param("groupSeasonId") Long groupSeasonId
    );

    /**
     * 종료 시즌에 속한 모든 개인 기여도를 조회합니다.
     * 그룹 시즌·그룹·사용자를 함께 로딩하여 정합성 검증과 순위 계산에 사용합니다.
     * 이 쿼리는 시즌 행 배타 잠금을 확보한 서비스에서 호출합니다.
     */
    @Query("""
            SELECT contribution
              FROM SeasonMemberContribution contribution
              JOIN FETCH contribution.groupSeason gs
              JOIN FETCH gs.group
              JOIN FETCH contribution.user
             WHERE gs.season.id = :seasonId
             ORDER BY gs.id ASC, contribution.user.id ASC
            """)
    List<SeasonMemberContribution> findAllForSeasonSnapshot(@Param("seasonId") Long seasonId);

    /**
     * 하나의 그룹 시즌에 속한 개인 랭킹 계산 대상을 조회합니다.
     * 탈퇴·강퇴자의 기록도 포함하고 사용자 정보를 함께 로딩합니다.
     *
     * @param groupSeasonId 조회할 그룹 시즌 ID
     * @return 해당 그룹 시즌의 전체 개인 기여도
     */
    @Query("""
            SELECT contribution
            FROM SeasonMemberContribution contribution
            JOIN FETCH contribution.user
            WHERE contribution.groupSeason.id = :groupSeasonId
            """)
    List<SeasonMemberContribution> findAllForMemberRanking(
            @Param("groupSeasonId") Long groupSeasonId
    );
}
