package com.team.sharedcreature._core.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;

/** 비정상 연결 종료에서도 Presence Session을 정리합니다. */
@Component
public class GroupPresenceLifecycleListener {

    private final GroupPresenceService presenceService;
    private final GroupLiveEventPublisher eventPublisher;

    /** Presence 상태와 그룹 이벤트 발행기를 주입받습니다. */
    public GroupPresenceLifecycleListener(
            GroupPresenceService presenceService,
            GroupLiveEventPublisher eventPublisher
    ) {
        this.presenceService = presenceService;
        this.eventPublisher = eventPublisher;
    }

    /** Transport 종료 시 마지막 사용자 Session이면 즉시 퇴장 이벤트를 발행합니다. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
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
}
