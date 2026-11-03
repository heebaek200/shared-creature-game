package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * OauthLoginAttempt 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface OauthLoginAttemptRepository extends JpaRepository<OauthLoginAttempt, Long> {
    Optional<OauthLoginAttempt> findByStateHash(String stateHash);
}
