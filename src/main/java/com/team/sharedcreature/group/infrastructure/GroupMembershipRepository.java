package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/**
 * GroupMembership 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {
    boolean existsByUserIdAndStatus(Long userId, String status);

    Optional<GroupMembership> findByUserIdAndStatus(Long userId, String status);

    @Query("""
            SELECT membership
              FROM GroupMembership membership
              JOIN FETCH membership.group
              JOIN FETCH membership.user
             WHERE membership.user.id = :userId
               AND membership.status = 'ACTIVE'
            """)
    Optional<GroupMembership> findActiveWithGroupByUserId(@Param("userId") Long userId);
}
