package com.team.sharedcreature.auth.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 활성 전체 관리자가 없는 실행환경의 최초 관리자 설정을 시작 시 검증합니다.
 */
@Component
@EnableConfigurationProperties(InitialSuperAdminProperties.class)
public class InitialSuperAdminValidator implements ApplicationRunner {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final InitialSuperAdminProperties properties;

    /** 최초 관리자 검증에 필요한 저장소와 설정을 주입받습니다. */
    public InitialSuperAdminValidator(
            UserRepository userRepository,
            SocialAccountRepository socialAccountRepository,
            InitialSuperAdminProperties properties
    ) {
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.properties = properties;
    }

    /** 활성 전체 관리자가 없을 때 설정 누락·충돌을 실행 실패로 처리합니다. */
    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByGlobalRoleAndStatus("SUPER_ADMIN", "ACTIVE")) {
            return;
        }
        if (!properties.isComplete()) {
            throw new IllegalStateException(
                    "활성 전체 관리자가 없으므로 INITIAL_SUPER_ADMIN_PROVIDER와 "
                            + "INITIAL_SUPER_ADMIN_PROVIDER_USER_ID를 모두 설정해야 합니다."
            );
        }
        if (!"GOOGLE".equalsIgnoreCase(properties.provider())) {
            throw new IllegalStateException("현재 최초 전체 관리자 공급자는 GOOGLE만 지원합니다.");
        }
        socialAccountRepository
                .findByProviderAndProviderUserId(properties.provider().toUpperCase(), properties.providerUserId())
                .ifPresent(account -> {
                    throw new IllegalStateException("최초 전체 관리자 대상이 이미 일반 사용자로 등록되어 있습니다.");
                });
    }
}
