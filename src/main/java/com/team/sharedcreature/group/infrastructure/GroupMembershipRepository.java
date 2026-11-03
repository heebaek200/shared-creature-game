package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * GroupMembership 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {
    boolean existsByUserIdAndStatus(Long userId, String status);

    Optional<GroupMembership> findByUserIdAndStatus(Long userId, String status);
}
