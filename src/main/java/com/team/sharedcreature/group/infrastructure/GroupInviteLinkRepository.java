package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupInviteLink;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GroupInviteLink 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupInviteLinkRepository extends JpaRepository<GroupInviteLink, Long> {
}
