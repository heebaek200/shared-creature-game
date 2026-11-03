package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Group 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupRepository extends JpaRepository<Group, Long> {
    boolean existsByNormalizedName(String normalizedName);

    Optional<Group> findByNormalizedName(String normalizedName);
}
