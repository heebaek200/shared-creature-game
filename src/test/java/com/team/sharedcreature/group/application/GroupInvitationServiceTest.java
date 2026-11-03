package com.team.sharedcreature.group.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.application.AuthService;
import com.team.sharedcreature.auth.application.PendingSignup;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

@ExtendWith(MockitoExtension.class)
class GroupInvitationServiceTest {

    private static final String VALID_TOKEN = "a".repeat(32);

    @Mock GroupInviteLinkRepository inviteLinkRepository;
    @Mock GroupMembershipRepository membershipRepository;
    @Mock GroupMembershipService membershipService;

    private GroupInvitationService service;

    @BeforeEach
    void setUp() {
        service = new GroupInvitationService(
                inviteLinkRepository,
                membershipRepository,
                membershipService,
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void 가입_대기에서_새_초대를_수락하면_마지막_초대로_동기화한다() {
        GroupInviteLink invitation = invitation(20L, 3L, 1, 20);
        when(inviteLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.PENDING_SIGNUP, new PendingSignup(
                "GOOGLE",
                "provider-user",
                "user@example.com",
                true,
                "가입자",
                10L,
                Instant.parse("2026-11-03T01:00:00Z")
        ));

        var result = service.acceptInvitation(VALID_TOKEN, session);

        assertThat(result.nextRoute()).isEqualTo("/signup");
        assertThat(((InvitationSessionContext) session.getAttribute(
                InvitationSessionContext.SESSION_KEY)).inviteLinkId()).isEqualTo(20L);
        assertThat(((PendingSignup) session.getAttribute(AuthService.PENDING_SIGNUP)).inviteLinkId()).isEqualTo(20L);
    }

    @Test
    void 정원이_찬_동일_그룹의_초대를_다시_수락하면_기존_성공_경로를_반환한다() {
        GroupInviteLink invitation = invitation(20L, 3L, 20, 20);
        when(inviteLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));
        GroupMembership membership = mock(GroupMembership.class);
        Group group = invitation.getGroup();
        when(membership.getGroup()).thenReturn(group);
        when(membershipRepository.findActiveWithGroupByUserId(1L)).thenReturn(Optional.of(membership));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "기존회원", Set.of("MEMBER"))
        );

        var result = service.acceptInvitation(VALID_TOKEN, session);

        assertThat(result.nextRoute()).isEqualTo("/creature");
        verify(membershipService, never()).joinInvitation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void 무효_초대_거절은_오류를_반환하기_전에_세션_문맥을_정리한다() {
        when(inviteLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InvitationSessionContext.SESSION_KEY, new InvitationSessionContext(10L));
        session.setAttribute(AuthService.PENDING_SIGNUP, new PendingSignup(
                "GOOGLE",
                "provider-user",
                "user@example.com",
                true,
                "가입자",
                10L,
                Instant.parse("2026-11-03T01:00:00Z")
        ));

        assertThatThrownBy(() -> service.rejectInvitation(VALID_TOKEN, session))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThat(session.getAttribute(InvitationSessionContext.SESSION_KEY)).isNull();
        assertThat(session.getAttribute(AuthService.PENDING_SIGNUP)).isNull();
    }

    @Test
    void 만료된_가입_대기에서_초대를_수락하면_대기를_제거하고_로그인으로_이동한다() {
        GroupInviteLink invitation = invitation(20L, 3L, 1, 20);
        when(inviteLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.PENDING_SIGNUP, new PendingSignup(
                "GOOGLE",
                "provider-user",
                "user@example.com",
                true,
                "가입자",
                10L,
                Instant.parse("2026-11-03T00:00:00Z")
        ));

        var result = service.acceptInvitation(VALID_TOKEN, session);

        assertThat(result.nextRoute()).isEqualTo("/login");
        assertThat(session.getAttribute(AuthService.PENDING_SIGNUP)).isNull();
        assertThat(session.getAttribute(InvitationSessionContext.SESSION_KEY))
                .isEqualTo(new InvitationSessionContext(20L));
    }

    @Test
    void 초대_Token_형식이_잘못되면_조회하지_않고_필드_오류를_반환한다() {
        MockHttpSession session = new MockHttpSession();

        assertThatThrownBy(() -> service.getPublicInvitation("invalid-token", session))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_FIELD_VALUE"));
        verify(inviteLinkRepository, never()).findByTokenHash(anyString());
    }

    private GroupInviteLink invitation(Long invitationId, Long groupId, int currentCount, int capacity) {
        Group group = mock(Group.class);
        lenient().when(group.getId()).thenReturn(groupId);
        lenient().when(group.getStatus()).thenReturn("ACTIVE");
        lenient().when(group.getCurrentMemberCount()).thenReturn(currentCount);
        lenient().when(group.getCapacity()).thenReturn(capacity);
        GroupInviteLink invitation = mock(GroupInviteLink.class);
        lenient().when(invitation.getId()).thenReturn(invitationId);
        lenient().when(invitation.getStatus()).thenReturn("ACTIVE");
        lenient().when(invitation.getGroup()).thenReturn(group);
        return invitation;
    }
}
