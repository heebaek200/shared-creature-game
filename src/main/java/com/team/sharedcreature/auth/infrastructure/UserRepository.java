package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * User 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
