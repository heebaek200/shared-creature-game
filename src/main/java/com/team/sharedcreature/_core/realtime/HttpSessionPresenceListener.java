package com.team.sharedcreature._core.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.session.events.SessionDestroyedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 로그아웃·만료로 HTTP Session이 제거되면 연결된 Presence도 제거합니다. */
@Component
public class HttpSessionPresenceListener {

    private final GroupPresenceService presenceService;
    private final GroupLiveEventPublisher eventPublisher;
    private final ActiveWebSocketConnectionRegistry connectionRegistry;

    /** Presence 상태와 그룹 이벤트 발행기를 주입받습니다. */
    public HttpSessionPresenceListener(
            GroupPresenceService presenceService,
            GroupLiveEventPublisher eventPublisher,
            ActiveWebSocketConnectionRegistry connectionRegistry
    ) {
        this.presenceService = presenceService;
        this.eventPublisher = eventPublisher;
        this.connectionRegistry = connectionRegistry;
    }

    /** 삭제·만료된 HTTP Session의 마지막 Presence를 즉시 퇴장 처리합니다. */
    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        presenceService.removeHttpSession(event.getSession().getId()).forEach(removal -> {
            connectionRegistry.close(removal.webSocketSessionId());
            if (removal.lastSession()) {
                eventPublisher.publish(
                        removal.groupId(),
                        "PRESENCE_LEFT",
                        Instant.now(),
                        new GroupLivePayloads.PresenceLeft(removal.userId())
                );
            }
        });
    }
}
