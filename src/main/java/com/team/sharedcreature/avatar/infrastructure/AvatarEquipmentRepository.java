package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AvatarEquipment 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface AvatarEquipmentRepository extends JpaRepository<AvatarEquipment, Long> {
}
