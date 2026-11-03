package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * User 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 지정한 전역 역할과 상태를 가진 계정이 존재하는지 확인합니다. */
    boolean existsByGlobalRoleAndStatus(String globalRole, String status);

    /** 가입 직렬화를 위해 사용자를 비관적 쓰기 잠금으로 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
