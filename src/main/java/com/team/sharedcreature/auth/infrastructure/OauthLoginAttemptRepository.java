package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/**
 * OauthLoginAttempt 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface OauthLoginAttemptRepository extends JpaRepository<OauthLoginAttempt, Long> {
    Optional<OauthLoginAttempt> findByStateHash(String stateHash);

    /** OAuth state의 최종 소비를 직렬화합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT attempt FROM OauthLoginAttempt attempt WHERE attempt.stateHash = :stateHash")
    Optional<OauthLoginAttempt> findByStateHashForUpdate(@Param("stateHash") String stateHash);
}
