package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * 마지막 인증 활동 시각이 지정된 기준 시각보다 오래된 경우에만 갱신합니다.
     *
     * 조건 검사와 UPDATE를 DB의 단일 명령으로 수행해 동시 요청의 중복 쓰기를 방지합니다.
     * User의 @Version과 충돌하지 않도록 버전도 함께 증가시키며,
     * 실제 갱신된 행의 수를 반환합니다.
     *
     * @param userId 갱신할 사용자 ID
     * @param now 현재 인증 활동 시각
     * @param threshold 마지막 갱신 허용 기준 시각
     * @return 실제 변경된 행 수(0 또는 1)
     */
    @Modifying
    @Query(value = """
        UPDATE users
        SET last_active_at = :now
          , updated_at     = :now
          , version        = version + 1
        WHERE id = :userId
          AND status = 'ACTIVE'
          AND last_active_at <= :threshold
          AND last_active_at < :now
        """, nativeQuery = true)
    int updateLastActiveAtIfDue(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            @Param("threshold") Instant threshold
    );
}
