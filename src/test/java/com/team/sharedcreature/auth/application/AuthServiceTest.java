package com.team.sharedcreature.auth.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void 만료된_OAuth_시도는_SESSION_EXPIRED로_구분한다() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        MockHttpSession session = new MockHttpSession();
        OauthLoginAttempt attempt = OauthLoginAttempt.start(
                Hashing.sha256("state"),
                Hashing.sha256(session.getId()),
                "GOOGLE",
                "LOGIN",
                null,
                "DEFAULT",
                now.minusSeconds(601)
        );
        AuthService service = callbackService(attempt, now);

        assertThatThrownBy(() -> service.handleCallback("state", "code", session))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OAUTH_SESSION_EXPIRED"));
    }

    @Test
    void 이미_사용한_OAuth_State는_STATE_INVALID로_구분한다() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        MockHttpSession session = new MockHttpSession();
        OauthLoginAttempt attempt = OauthLoginAttempt.start(
                Hashing.sha256("state"),
                Hashing.sha256(session.getId()),
                "GOOGLE",
                "LOGIN",
                null,
                "DEFAULT",
                now
        );
        attempt.consume(now);
        AuthService service = callbackService(attempt, now);

        assertThatThrownBy(() -> service.handleCallback("state", "code", session))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OAUTH_STATE_INVALID"));
    }

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

    @SuppressWarnings("unchecked")
    private AuthService callbackService(OauthLoginAttempt attempt, Instant now) {
        OauthLoginAttemptRepository attemptRepository = mock(OauthLoginAttemptRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(attemptRepository.findByStateHash(Hashing.sha256("state"))).thenReturn(java.util.Optional.of(attempt));
        doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        return new AuthService(
                attemptRepository,
                mock(SocialAccountRepository.class),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                mock(GroupMembershipRepository.class),
                mock(GroupInviteLinkRepository.class),
                mock(GroupJoinRestrictionRepository.class),
                mock(GroupMembershipService.class),
                mock(SocialLoginClient.class),
                mock(InitialSuperAdminProperties.class),
                transactionTemplate,
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }
}
