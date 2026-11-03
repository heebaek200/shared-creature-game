package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * UserProfile 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    boolean existsByNormalizedNickname(String normalizedNickname);

    Optional<UserProfile> findByUserId(Long userId);
}
