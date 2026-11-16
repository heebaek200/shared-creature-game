package com.team.sharedcreature._core.realtime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/** 탈퇴·강퇴 Commit 이후 사용자의 그룹 Presence를 회수합니다. */
@Service
public class GroupPresenceRevocationService {

    private final GroupPresenceService presenceService;
    private final GroupLiveEventPublisher eventPublisher;
    private final ActiveWebSocketConnectionRegistry connectionRegistry;

    /** Presence 상태와 그룹 이벤트 발행기를 주입받습니다. */
    public GroupPresenceRevocationService(
            GroupPresenceService presenceService,
            GroupLiveEventPublisher eventPublisher,
            ActiveWebSocketConnectionRegistry connectionRegistry
    ) {
        this.presenceService = presenceService;
        this.eventPublisher = eventPublisher;
        this.connectionRegistry = connectionRegistry;
    }

    /** 현재 트랜잭션이 Commit된 뒤 사용자의 모든 Presence를 제거합니다. */
    public void revokeAfterCommit(Long userId, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Presence 권한 회수는 활성 트랜잭션 안에서 예약해야 합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                presenceService.removeUser(userId).forEach(removal -> {
                    connectionRegistry.close(removal.webSocketSessionId());
                    if (removal.lastSession()) {
                        eventPublisher.publish(
                                removal.groupId(),
                                "PRESENCE_LEFT",
                                occurredAt,
                                new GroupLivePayloads.PresenceLeft(removal.userId())
                        );
                    }
                });
            }
        });
    }
}
