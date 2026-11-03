package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupAdminChange;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GroupAdminChange 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupAdminChangeRepository extends JpaRepository<GroupAdminChange, Long> {
}
