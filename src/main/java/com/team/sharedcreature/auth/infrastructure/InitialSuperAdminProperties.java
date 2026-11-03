package com.team.sharedcreature.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최초 전체 관리자를 식별하는 소셜 공급자 불변 정보입니다.
 *
 * @param provider 소셜 공급자 코드
 * @param providerUserId 공급자의 불변 사용자 ID
 */
@ConfigurationProperties("auth.initial-super-admin")
public record InitialSuperAdminProperties(String provider, String providerUserId) {

    /** 두 설정값이 모두 입력됐는지 확인합니다. */
    public boolean isComplete() {
        return provider != null && !provider.isBlank()
                && providerUserId != null && !providerUserId.isBlank();
    }

    /** 검증된 공급자 계정이 최초 관리자 설정과 일치하는지 확인합니다. */
    public boolean matches(String candidateProvider, String candidateProviderUserId) {
        return isComplete()
                && provider.equalsIgnoreCase(candidateProvider)
                && providerUserId.equals(candidateProviderUserId);
    }
}
