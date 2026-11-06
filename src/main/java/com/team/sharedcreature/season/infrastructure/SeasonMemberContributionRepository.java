package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
