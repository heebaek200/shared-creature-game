package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.UserActivityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * UserActivityEvent 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserActivityEventRepository extends JpaRepository<UserActivityEvent, Long> {
}
