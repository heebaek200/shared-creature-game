package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * User 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 지정한 전역 역할과 상태를 가진 계정이 존재하는지 확인합니다. */
    boolean existsByGlobalRoleAndStatus(String globalRole, String status);
}
