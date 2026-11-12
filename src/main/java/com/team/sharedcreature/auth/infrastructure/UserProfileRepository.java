package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * UserProfile 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    boolean existsByNormalizedNickname(String normalizedNickname);

    Optional<UserProfile> findByUserId(Long userId);

    /**
     * Snapshot 배치에서 실제로 새로 저장할 사용자들의 프로필만 조회합니다.
     * 한 사용자가 여러 그룹 시즌에 기여했더라도 프로필은 한 번만 읽습니다.
     * 기존 스냅샷의 닉네임은 최신 프로필로 덮어쓰지 않습니다.
     */
    List<UserProfile> findAllByUserIdIn(Collection<Long> userIds);
}
