package com.team.sharedcreature.auth.application;

import com.team.sharedcreature.auth.infrastructure.InitialSuperAdminProperties;
import com.team.sharedcreature.auth.infrastructure.OauthLoginAttemptRepository;
import com.team.sharedcreature.auth.infrastructure.SocialAccountRepository;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthServiceTest {

    @Test
    void 초대_가입_실패_사유는_한_번만_반환하고_세션에서_제거한다() {
        AuthService service = new AuthService(
                mock(OauthLoginAttemptRepository.class),
                mock(SocialAccountRepository.class),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                mock(GroupMembershipRepository.class),
                mock(GroupInviteLinkRepository.class),
                mock(GroupJoinRestrictionRepository.class),
                mock(GroupMembershipService.class),
                mock(SocialLoginClient.class),
                mock(InitialSuperAdminProperties.class),
                mock(TransactionTemplate.class),
                mock(Clock.class)
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.ONBOARDING_REASON, "ALREADY_IN_GROUP");

        assertThat(service.consumeOnboardingReason(session)).isEqualTo("ALREADY_IN_GROUP");
        assertThat(service.consumeOnboardingReason(session)).isNull();
        assertThat(session.getAttribute(AuthService.ONBOARDING_REASON)).isNull();
    }
}
