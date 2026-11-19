package com.team.sharedcreature.auth.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
import com.team.sharedcreature.auth.domain.OauthLoginIntent;
import com.team.sharedcreature.auth.domain.SocialProvider;
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
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void 만료된_OAuth_시도는_SESSION_EXPIRED로_구분한다() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        MockHttpSession session = new MockHttpSession();
        OauthLoginAttempt attempt = OauthLoginAttempt.start(
                Hashing.sha256("state"),
                Hashing.sha256(session.getId()),
                SocialProvider.GOOGLE,
                OauthLoginIntent.LOGIN,
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
                SocialProvider.GOOGLE,
                OauthLoginIntent.LOGIN,
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
                List.of(mock(SocialLoginClient.class)),
                new InitialSuperAdminProperties(null, null),
                mock(TransactionTemplate.class),
                mock(Clock.class),
                mock(FindByIndexNameSessionRepository.class)
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.ONBOARDING_REASON, "ALREADY_IN_GROUP");

        assertThat(service.consumeOnboardingReason(session)).isEqualTo("ALREADY_IN_GROUP");
        assertThat(service.consumeOnboardingReason(session)).isNull();
        assertThat(session.getAttribute(AuthService.ONBOARDING_REASON)).isNull();
    }

    @Test
    void naverLoginKeepsProviderFromAttemptThroughSignup() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        var session = new MockHttpSession();
        var attempts = mock(OauthLoginAttemptRepository.class);
        var accounts = mock(SocialAccountRepository.class);
        var client = mock(SocialLoginClient.class);
        when(client.provider()).thenReturn("NAVER");
        when(client.authorizationUrl(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(client.exchange(eq("code"), any(), any())).thenReturn(
                new SocialLoginClient.SocialProfile("naver-user", "member@example.com", true, "member"));
        AuthService service = callbackService(attempts, accounts, client, now);

        String state = service.createOAuthAttempt("NAVER", "DEFAULT", session).authorizationUrl();
        var savedAttempt = ArgumentCaptor.forClass(OauthLoginAttempt.class);
        verify(attempts).save(savedAttempt.capture());
        OauthLoginAttempt attempt = savedAttempt.getValue();
        assertThat(attempt.getProvider()).isEqualTo(SocialProvider.NAVER);
        String stateHash = Hashing.sha256(state);
        when(attempts.findByStateHash(stateHash)).thenReturn(java.util.Optional.of(attempt));
        when(attempts.findByStateHashForUpdate(stateHash)).thenReturn(java.util.Optional.of(attempt));

        assertThat(service.handleCallback(state, "code", session)).isEqualTo("/");

        verify(accounts).findByProviderAndProviderUserId(SocialProvider.NAVER, "naver-user");
        assertThat(session.getAttribute(AuthService.PENDING_SIGNUP)).isInstanceOfSatisfying(
                PendingSignup.class, pending -> assertThat(pending.provider()).isEqualTo("NAVER"));
    }

    private AuthService callbackService(OauthLoginAttempt attempt, Instant now) {
        OauthLoginAttemptRepository attemptRepository = mock(OauthLoginAttemptRepository.class);
        when(attemptRepository.findByStateHash(Hashing.sha256("state"))).thenReturn(java.util.Optional.of(attempt));
        return callbackService(attemptRepository, mock(SocialAccountRepository.class), mock(SocialLoginClient.class), now);
    }

    @SuppressWarnings("unchecked")
    private AuthService callbackService(OauthLoginAttemptRepository attemptRepository,
                                        SocialAccountRepository accountRepository, SocialLoginClient client, Instant now) {
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> action = invocation.getArgument(0);
            return action.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any(TransactionCallback.class));
        return new AuthService(
                attemptRepository,
                accountRepository,
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                mock(GroupMembershipRepository.class),
                mock(GroupInviteLinkRepository.class),
                mock(GroupJoinRestrictionRepository.class),
                mock(GroupMembershipService.class),
                List.of(client),
                new InitialSuperAdminProperties(null, null),
                transactionTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                mock(FindByIndexNameSessionRepository.class)
        );
    }
}
