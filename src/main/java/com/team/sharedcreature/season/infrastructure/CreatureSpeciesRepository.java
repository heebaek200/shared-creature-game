package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.CreatureSpecies;
import com.team.sharedcreature.season.domain.CreatureSpeciesStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * CreatureSpecies 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface CreatureSpeciesRepository extends JpaRepository<CreatureSpecies, Long> {
    List<CreatureSpecies> findAllByStatusOrderByDisplayOrderAscIdAsc(CreatureSpeciesStatus status);

    Optional<CreatureSpecies> findByIdAndStatus(Long id, CreatureSpeciesStatus status);
}
