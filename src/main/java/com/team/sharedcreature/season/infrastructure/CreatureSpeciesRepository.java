package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.CreatureSpecies;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * CreatureSpecies 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface CreatureSpeciesRepository extends JpaRepository<CreatureSpecies, Long> {
}
