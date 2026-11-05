package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupJoinRestriction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * GroupJoinRestriction 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupJoinRestrictionRepository extends JpaRepository<GroupJoinRestriction, Long> {
    boolean existsByUserIdAndRestrictedUntilAfter(Long userId, Instant now);

    Optional<GroupJoinRestriction> findFirstByUserIdAndRestrictedUntilAfterOrderByRestrictedUntilDesc(
            Long userId,
            Instant now
    );

    /**
     * 사용자별 기존 가입 제한 행을 쓰기 잠금으로 조회
     * - 제한 기간이 이미 끝난 행도 다시 사용함에 유의
     * - 호출하는 곳에서 동일 사용자 행을 먼저 잠가 중복 생성 경합을 방지해야 함
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                    SELECT
                        gjr
                    FROM
                        GroupJoinRestriction AS gjr
                    WHERE
                        gjr.user.id = :userId
            """)
    Optional<GroupJoinRestriction> findByUserIdForUpdate(@Param("userId") Long userId);
}
