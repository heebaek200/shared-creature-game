package com.team.sharedcreature._core.realtime;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.season.api.CreatureHomeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupStompAuthorizationInterceptorTest {

    private final GroupMembershipRepository membershipRepository =
            mock(GroupMembershipRepository.class);
    private final GroupPresenceService presenceService = new GroupPresenceService();
    private final PresenceMemberPayloadProvider payloadProvider =
            mock(PresenceMemberPayloadProvider.class);
    private final GroupLiveEventPublisher eventPublisher = mock(GroupLiveEventPublisher.class);
    private final MessageChannel channel = mock(MessageChannel.class);

    private GroupStompAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new GroupStompAuthorizationInterceptor(
                membershipRepository,
                presenceService,
                payloadProvider,
                eventPublisher
        );
    }

    @Test
    void CONNECT는_세션_CSRF와_Native_Header가_같아야_한다() {
        Message<byte[]> valid = message(StompCommand.CONNECT, null, null, "csrf-token");

        assertThat(interceptor.preSend(valid, channel)).isSameAs(valid);

        Message<byte[]> invalid = message(StompCommand.CONNECT, null, null, "other-token");
        assertThatThrownBy(() -> interceptor.preSend(invalid, channel))
                .isInstanceOfSatisfying(StompAccessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CSRF_INVALID"));
    }

    @Test
    void 현재_활성_그룹만_구독하고_첫_Session에서_입장_이벤트를_발행한다() {
        GroupMembership membership = activeMembership(20L);
        when(membershipRepository.findActiveWithGroupByUserId(10L))
                .thenReturn(Optional.of(membership));
        GroupLivePayloads.PresenceJoined payload = new GroupLivePayloads.PresenceJoined(
                10L,
                "새싹",
                1,
                30L,
                new CreatureHomeResponse.EquipmentSlotsResponse(null, null, null, null)
        );
        when(payloadProvider.getPresenceJoined(20L, 10L)).thenReturn(payload);
        Message<byte[]> subscribe = message(
                StompCommand.SUBSCRIBE,
                "/topic/groups/20/live",
                "sub-1",
                null
        );

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);

        assertThat(presenceService.activeUserIds(20L)).containsExactly(10L);
        verify(eventPublisher).publish(eq(20L), eq("PRESENCE_JOINED"), any(), eq(payload));
    }

    @Test
    void 다른_그룹_구독은_ROLE_FORBIDDEN이다() {
        GroupMembership membership = activeMembership(20L);
        when(membershipRepository.findActiveWithGroupByUserId(10L))
                .thenReturn(Optional.of(membership));
        Message<byte[]> subscribe = message(
                StompCommand.SUBSCRIBE,
                "/topic/groups/21/live",
                "sub-1",
                null
        );

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOfSatisfying(StompAccessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ROLE_FORBIDDEN"));
    }

    @Test
    void 클라이언트_SEND는_INVALID_REQUEST이다() {
        Message<byte[]> send = message(StompCommand.SEND, "/topic/groups/20/live", null, null);

        assertThatThrownBy(() -> interceptor.preSend(send, channel))
                .isInstanceOfSatisfying(StompAccessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));
    }

    private Message<byte[]> message(
            StompCommand command,
            String destination,
            String subscriptionId,
            String csrfToken
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("ws-1");
        accessor.setSessionAttributes(sessionAttributes());
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        if (csrfToken != null) {
            accessor.setNativeHeader("X-CSRF-TOKEN", csrfToken);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Map<String, Object> sessionAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(
                WebSocketSessionHandshakeInterceptor.LOGIN_USER_ATTRIBUTE,
                new LoginUser(10L, "새싹", Set.of("MEMBER"))
        );
        attributes.put(WebSocketSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTRIBUTE, "http-1");
        attributes.put(WebSocketSessionHandshakeInterceptor.CSRF_TOKEN_ATTRIBUTE, "csrf-token");
        return attributes;
    }

    private GroupMembership activeMembership(Long groupId) {
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(groupId);
        when(group.getStatus()).thenReturn("ACTIVE");
        GroupMembership membership = mock(GroupMembership.class);
        when(membership.getGroup()).thenReturn(group);
        return membership;
    }
}
