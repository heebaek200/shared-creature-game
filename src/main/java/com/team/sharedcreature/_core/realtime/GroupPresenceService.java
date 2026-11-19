package com.team.sharedcreature._core.realtime;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 사용자별 활성 WebSocket 구독과 Presence를 단일 서버 메모리에서 관리합니다. */
@Service
public class GroupPresenceService {

    private final Map<String, SessionPresence> sessions = new HashMap<>();
    private final Map<GroupUserKey, Set<String>> userSessions = new HashMap<>();

    /** 기존 호출부가 사용하는 live 구독을 등록합니다. */
    public synchronized Registration register(
            String webSocketSessionId,
            String httpSessionId,
            String subscriptionId,
            Long userId,
            Long groupId
    ) {
        return register(webSocketSessionId, httpSessionId, subscriptionId, userId, groupId,
                SubscriptionType.LIVE);
    }

    /** 연결당 live와 chat 구독을 하나씩 등록하고 live의 첫 사용자 Session인지 반환합니다. */
    public synchronized Registration register(
            String webSocketSessionId,
            String httpSessionId,
            String subscriptionId,
            Long userId,
            Long groupId,
            SubscriptionType type
    ) {
        SessionPresence current = sessions.get(webSocketSessionId);
        if (current == null) {
            current = new SessionPresence(httpSessionId, userId, groupId);
            sessions.put(webSocketSessionId, current);
        } else if (!current.matches(httpSessionId, userId, groupId)) {
            throw new IllegalStateException("하나의 WebSocket 연결은 같은 사용자와 그룹만 구독할 수 있습니다.");
        }
        if (current.hasSubscription(subscriptionId) || current.hasType(type)) {
            throw new IllegalStateException("같은 종류의 그룹 구독은 하나만 등록할 수 있습니다.");
        }

        current.add(subscriptionId, type);
        if (type == SubscriptionType.CHAT) {
            return new Registration(groupId, userId, false);
        }

        GroupUserKey key = new GroupUserKey(groupId, userId);
        Set<String> activeSessions = userSessions.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        boolean firstSession = activeSessions.isEmpty();
        activeSessions.add(webSocketSessionId);
        return new Registration(groupId, userId, firstSession);
    }

    /** 같은 WebSocket 연결이 등록한 구독 ID일 때만 해당 Presence를 제거합니다. */
    public synchronized Optional<Removal> removeSubscription(
            String webSocketSessionId,
            String subscriptionId
    ) {
        SessionPresence current = sessions.get(webSocketSessionId);
        if (current == null || !current.hasSubscription(subscriptionId)) {
            throw new IllegalStateException("등록되지 않은 그룹 구독은 해제할 수 없습니다.");
        }
        SubscriptionType removedType = current.remove(subscriptionId);
        boolean lastSession = removedType == SubscriptionType.LIVE
                && removeLiveSession(webSocketSessionId, current.groupId(), current.userId());
        if (current.isEmpty()) {
            sessions.remove(webSocketSessionId);
        }
        return Optional.of(new Removal(
                webSocketSessionId,
                current.groupId(),
                current.userId(),
                lastSession
        ));
    }

    /** WebSocket Session을 제거하고 마지막 Session이었는지 반환합니다. */
    public synchronized Optional<Removal> removeSession(String webSocketSessionId) {
        SessionPresence removed = sessions.remove(webSocketSessionId);
        if (removed == null) {
            return Optional.empty();
        }
        boolean lastSession = removed.hasType(SubscriptionType.LIVE)
                && removeLiveSession(webSocketSessionId, removed.groupId(), removed.userId());
        return Optional.of(new Removal(
                webSocketSessionId,
                removed.groupId(),
                removed.userId(),
                lastSession
        ));
    }

    private boolean removeLiveSession(String webSocketSessionId, Long groupId, Long userId) {
        GroupUserKey key = new GroupUserKey(groupId, userId);
        Set<String> activeSessions = userSessions.get(key);
        if (activeSessions == null) {
            return true;
        }
        activeSessions.remove(webSocketSessionId);
        boolean lastSession = activeSessions.isEmpty();
        if (lastSession) {
            userSessions.remove(key);
        }
        return lastSession;
    }

    /** 로그아웃·세션 만료 시 같은 HTTP Session에서 생성한 연결을 모두 제거합니다. */
    public synchronized List<Removal> removeHttpSession(String httpSessionId) {
        List<String> targets = sessions.entrySet().stream()
                .filter(entry -> httpSessionId.equals(entry.getValue().httpSessionId()))
                .map(Map.Entry::getKey)
                .toList();
        return removeSessions(targets);
    }

    /** 탈퇴·강퇴 시 사용자의 모든 활성 연결을 Presence에서 제거합니다. */
    public synchronized List<Removal> removeUser(Long userId) {
        List<String> targets = sessions.entrySet().stream()
                .filter(entry -> userId.equals(entry.getValue().userId()))
                .map(Map.Entry::getKey)
                .toList();
        return removeSessions(targets);
    }

    /** 그룹에 접속 중인 사용자 ID Snapshot을 반환합니다. */
    public synchronized List<Long> activeUserIds(Long groupId) {
        return userSessions.keySet().stream()
                .filter(key -> groupId.equals(key.groupId()))
                .map(GroupUserKey::userId)
                .sorted()
                .toList();
    }

    private List<Removal> removeSessions(List<String> sessionIds) {
        List<Removal> removals = new ArrayList<>();
        for (String sessionId : sessionIds) {
            removeSession(sessionId).ifPresent(removals::add);
        }
        return List.copyOf(removals);
    }

    private static final class SessionPresence {

        private final String httpSessionId;
        private final Long userId;
        private final Long groupId;
        private final Map<String, SubscriptionType> subscriptions = new HashMap<>();

        private SessionPresence(String httpSessionId, Long userId, Long groupId) {
            this.httpSessionId = httpSessionId;
            this.userId = userId;
            this.groupId = groupId;
        }

        private boolean matches(String candidateHttpSessionId, Long candidateUserId, Long candidateGroupId) {
            return java.util.Objects.equals(httpSessionId, candidateHttpSessionId)
                    && java.util.Objects.equals(userId, candidateUserId)
                    && java.util.Objects.equals(groupId, candidateGroupId);
        }

        private boolean hasSubscription(String subscriptionId) {
            return subscriptions.containsKey(subscriptionId);
        }

        private boolean hasType(SubscriptionType type) {
            return subscriptions.containsValue(type);
        }

        private void add(String subscriptionId, SubscriptionType type) {
            subscriptions.put(subscriptionId, type);
        }

        private SubscriptionType remove(String subscriptionId) {
            return subscriptions.remove(subscriptionId);
        }

        private boolean isEmpty() {
            return subscriptions.isEmpty();
        }

        private String httpSessionId() {
            return httpSessionId;
        }

        private Long userId() {
            return userId;
        }

        private Long groupId() {
            return groupId;
        }
    }

    private record GroupUserKey(Long groupId, Long userId) {
    }

    /** 구독 등록 결과입니다. */
    public record Registration(Long groupId, Long userId, boolean firstSession) {
    }

    /** Session 제거 결과입니다. */
    public record Removal(String webSocketSessionId, Long groupId, Long userId, boolean lastSession) {
    }

    /** 허용하는 그룹 STOMP 구독 종류입니다. */
    public enum SubscriptionType {
        LIVE,
        CHAT
    }
}
