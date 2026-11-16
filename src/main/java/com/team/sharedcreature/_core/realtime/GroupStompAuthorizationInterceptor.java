package com.team.sharedcreature._core.realtime;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** STOMP 인증·CSRF·현재 그룹 구독과 허용 프레임을 검사합니다. */
@Component
public class GroupStompAuthorizationInterceptor implements ChannelInterceptor {

    private static final Pattern GROUP_DESTINATION =
            Pattern.compile("^/topic/groups/([1-9][0-9]*)/live$");

    private final GroupMembershipRepository membershipRepository;
    private final GroupPresenceService presenceService;
    private final PresenceMemberPayloadProvider payloadProvider;
    private final GroupLiveEventPublisher eventPublisher;

    /** 권한 저장소와 Presence·이벤트 경계를 주입받습니다. */
    public GroupStompAuthorizationInterceptor(
            GroupMembershipRepository membershipRepository,
            GroupPresenceService presenceService,
            PresenceMemberPayloadProvider payloadProvider,
            @Lazy GroupLiveEventPublisher eventPublisher
    ) {
        this.membershipRepository = membershipRepository;
        this.presenceService = presenceService;
        this.payloadProvider = payloadProvider;
        this.eventPublisher = eventPublisher;
    }

    /** 허용한 STOMP 명령만 통과시키고 구독·해제에 Presence를 연동합니다. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        return switch (command) {
            case CONNECT -> validateConnect(message, accessor);
            case SUBSCRIBE -> registerSubscription(message, accessor);
            case UNSUBSCRIBE, DISCONNECT -> removeSubscription(message, accessor);
            default -> throw new StompAccessException("INVALID_REQUEST");
        };
    }

    /** Broker가 SUBSCRIBE Frame을 수락하지 못하면 먼저 등록한 Presence를 되돌립니다. */
    @Override
    public void afterSendCompletion(
            Message<?> message,
            MessageChannel channel,
            boolean sent,
            Exception exception
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != StompCommand.SUBSCRIBE || (sent && exception == null)) {
            return;
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }
        presenceService.removeSession(sessionId)
                .filter(GroupPresenceService.Removal::lastSession)
                .ifPresent(removal -> eventPublisher.publish(
                        removal.groupId(),
                        "PRESENCE_LEFT",
                        Instant.now(),
                        new GroupLivePayloads.PresenceLeft(removal.userId())
                ));
    }

    private Message<?> validateConnect(Message<?> message, StompHeaderAccessor accessor) {
        LoginUser loginUser = loginUser(accessor);
        if (loginUser == null) {
            throw new StompAccessException("AUTH_REQUIRED");
        }
        String expectedToken = sessionAttributes(accessor).get(
                WebSocketSessionHandshakeInterceptor.CSRF_TOKEN_ATTRIBUTE) instanceof String token
                ? token
                : null;
        String suppliedToken = accessor.getFirstNativeHeader("X-CSRF-TOKEN");
        if (expectedToken == null || !Objects.equals(expectedToken, suppliedToken)) {
            throw new StompAccessException("CSRF_INVALID");
        }
        return message;
    }

    private Message<?> registerSubscription(Message<?> message, StompHeaderAccessor accessor) {
        LoginUser loginUser = requireLoginUser(accessor);
        Long groupId = groupId(accessor.getDestination());
        GroupMembership membership = membershipRepository.findActiveWithGroupByUserId(loginUser.id())
                .orElseThrow(() -> new StompAccessException("ROLE_FORBIDDEN"));
        if (!groupId.equals(membership.getGroup().getId())
                || !"ACTIVE".equals(membership.getGroup().getStatus())) {
            throw new StompAccessException("ROLE_FORBIDDEN");
        }

        String webSocketSessionId = requireSessionId(accessor);
        String httpSessionId = sessionAttributes(accessor).get(
                WebSocketSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTRIBUTE) instanceof String value
                ? value
                : "";
        GroupPresenceService.Registration registration;
        try {
            registration = presenceService.register(
                    webSocketSessionId,
                    httpSessionId,
                    requireSubscriptionId(accessor),
                    loginUser.id(),
                    groupId
            );
        } catch (IllegalStateException exception) {
            throw new StompAccessException("INVALID_REQUEST");
        }
        if (registration.firstSession()) {
            try {
                GroupLivePayloads.PresenceJoined payload =
                        payloadProvider.getPresenceJoined(groupId, loginUser.id());
                eventPublisher.publish(groupId, "PRESENCE_JOINED", Instant.now(), payload);
            } catch (RuntimeException exception) {
                presenceService.removeSession(webSocketSessionId);
                throw exception;
            }
        }
        return message;
    }

    private Message<?> removeSubscription(Message<?> message, StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            java.util.Optional<GroupPresenceService.Removal> removalResult;
            try {
                removalResult = accessor.getCommand() == StompCommand.UNSUBSCRIBE
                        ? presenceService.removeSubscription(sessionId, requireSubscriptionId(accessor))
                        : presenceService.removeSession(sessionId);
            } catch (IllegalStateException exception) {
                throw new StompAccessException("INVALID_REQUEST");
            }
            removalResult
                    .filter(GroupPresenceService.Removal::lastSession)
                    .ifPresent(removal -> eventPublisher.publish(
                            removal.groupId(),
                            "PRESENCE_LEFT",
                            Instant.now(),
                            new GroupLivePayloads.PresenceLeft(removal.userId())
                    ));
        }
        return message;
    }

    private LoginUser requireLoginUser(StompHeaderAccessor accessor) {
        LoginUser loginUser = loginUser(accessor);
        if (loginUser == null) {
            throw new StompAccessException("AUTH_REQUIRED");
        }
        return loginUser;
    }

    private LoginUser loginUser(StompHeaderAccessor accessor) {
        Object value = sessionAttributes(accessor).get(
                WebSocketSessionHandshakeInterceptor.LOGIN_USER_ATTRIBUTE);
        return value instanceof LoginUser loginUser ? loginUser : null;
    }

    private Map<String, Object> sessionAttributes(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw new StompAccessException("AUTH_REQUIRED");
        }
        return attributes;
    }

    private Long groupId(String destination) {
        Matcher matcher = GROUP_DESTINATION.matcher(destination == null ? "" : destination);
        if (!matcher.matches()) {
            throw new StompAccessException("INVALID_REQUEST");
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new StompAccessException("INVALID_REQUEST");
        }
    }

    private String requireSessionId(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new StompAccessException("INVALID_REQUEST");
        }
        return sessionId;
    }

    private String requireSubscriptionId(StompHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new StompAccessException("INVALID_REQUEST");
        }
        return subscriptionId;
    }
}
