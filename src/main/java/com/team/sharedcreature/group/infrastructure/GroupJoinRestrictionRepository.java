package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupJoinRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;

/**
 * GroupJoinRestriction 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupJoinRestrictionRepository extends JpaRepository<GroupJoinRestriction, Long> {
    boolean existsByUserIdAndRestrictedUntilAfter(Long userId, Instant now);

    Optional<GroupJoinRestriction> findFirstByUserIdAndRestrictedUntilAfterOrderByRestrictedUntilDesc(
            Long userId,
            Instant now
    );
}
