package com.team.sharedcreature._core.realtime;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 사용자별 활성 WebSocket Session을 단일 서버 메모리에서 관리합니다. */
@Service
public class GroupPresenceService {

    private final Map<String, SessionPresence> sessions = new HashMap<>();
    private final Map<GroupUserKey, Set<String>> userSessions = new HashMap<>();

    /** 연결당 하나의 그룹 구독을 등록하고 첫 사용자 Session인지 반환합니다. */
    public synchronized Registration register(
            String webSocketSessionId,
            String httpSessionId,
            String subscriptionId,
            Long userId,
            Long groupId
    ) {
        SessionPresence current = sessions.get(webSocketSessionId);
        if (current != null) {
            throw new IllegalStateException("WebSocket 연결에는 그룹 구독을 하나만 등록할 수 있습니다.");
        }

        GroupUserKey key = new GroupUserKey(groupId, userId);
        Set<String> activeSessions = userSessions.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        boolean firstSession = activeSessions.isEmpty();
        activeSessions.add(webSocketSessionId);
        sessions.put(webSocketSessionId, new SessionPresence(
                httpSessionId,
                subscriptionId,
                userId,
                groupId
        ));
        return new Registration(groupId, userId, firstSession);
    }

    /** 같은 WebSocket 연결이 등록한 구독 ID일 때만 해당 Presence를 제거합니다. */
    public synchronized Optional<Removal> removeSubscription(
            String webSocketSessionId,
            String subscriptionId
    ) {
        SessionPresence current = sessions.get(webSocketSessionId);
        if (current == null || !current.subscriptionId().equals(subscriptionId)) {
            throw new IllegalStateException("등록되지 않은 그룹 구독은 해제할 수 없습니다.");
        }
        return removeSession(webSocketSessionId);
    }

    /** WebSocket Session을 제거하고 마지막 Session이었는지 반환합니다. */
    public synchronized Optional<Removal> removeSession(String webSocketSessionId) {
        SessionPresence removed = sessions.remove(webSocketSessionId);
        if (removed == null) {
            return Optional.empty();
        }
        GroupUserKey key = new GroupUserKey(removed.groupId(), removed.userId());
        Set<String> activeSessions = userSessions.get(key);
        if (activeSessions == null) {
            return Optional.of(new Removal(
                    webSocketSessionId,
                    removed.groupId(),
                    removed.userId(),
                    true
            ));
        }
        activeSessions.remove(webSocketSessionId);
        boolean lastSession = activeSessions.isEmpty();
        if (lastSession) {
            userSessions.remove(key);
        }
        return Optional.of(new Removal(
                webSocketSessionId,
                removed.groupId(),
                removed.userId(),
                lastSession
        ));
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

    private record SessionPresence(
            String httpSessionId,
            String subscriptionId,
            Long userId,
            Long groupId
    ) {
    }

    private record GroupUserKey(Long groupId, Long userId) {
    }

    /** 구독 등록 결과입니다. */
    public record Registration(Long groupId, Long userId, boolean firstSession) {
    }

    /** Session 제거 결과입니다. */
    public record Removal(String webSocketSessionId, Long groupId, Long userId, boolean lastSession) {
    }
}
