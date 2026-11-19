package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.SocialAccount;
import com.team.sharedcreature.auth.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * SocialAccount 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    /** 인증 공급자와 해당 공급자의 불변 사용자 ID로 소셜 계정을 조회합니다. */
    Optional<SocialAccount> findByProviderAndProviderUserId(
            SocialProvider provider,
            String providerUserId
    );

    Optional<SocialAccount> findByNormalizedEmail(String normalizedEmail);
}
