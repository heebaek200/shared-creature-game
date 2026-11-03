package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * SocialAccount 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    Optional<SocialAccount> findByNormalizedEmail(String normalizedEmail);
}
