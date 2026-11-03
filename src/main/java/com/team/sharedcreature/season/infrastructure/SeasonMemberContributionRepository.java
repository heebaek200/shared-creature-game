package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SeasonMemberContribution 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonMemberContributionRepository extends JpaRepository<SeasonMemberContribution, Long> {
}
