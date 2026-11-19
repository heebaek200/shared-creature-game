package com.team.sharedcreature._core.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GroupPresenceRevocationServiceTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void Commit_후_live_chat_다중_탭을_모두_닫고_Presence는_한_번만_퇴장시킨다() {
        GroupPresenceService presence = new GroupPresenceService();
        presence.register("ws-both", "http-1", "live-1", 10L, 20L,
                GroupPresenceService.SubscriptionType.LIVE);
        presence.register("ws-both", "http-1", "chat-1", 10L, 20L,
                GroupPresenceService.SubscriptionType.CHAT);
        presence.register("ws-live", "http-2", "live-2", 10L, 20L,
                GroupPresenceService.SubscriptionType.LIVE);
        presence.register("ws-chat", "http-3", "chat-3", 10L, 20L,
                GroupPresenceService.SubscriptionType.CHAT);
        presence.register("ws-other", "http-4", "live-4", 11L, 20L,
                GroupPresenceService.SubscriptionType.LIVE);
        GroupLiveEventPublisher publisher = mock(GroupLiveEventPublisher.class);
        ActiveWebSocketConnectionRegistry connections = mock(ActiveWebSocketConnectionRegistry.class);
        GroupPresenceRevocationService service = new GroupPresenceRevocationService(
                presence, publisher, connections);
        Instant occurredAt = Instant.parse("2026-11-19T00:00:00Z");
        beginTransaction();

        service.revokeAfterCommit(10L, occurredAt);

        assertThat(presence.activeUserIds(20L)).containsExactly(10L, 11L);
        verify(connections, never()).close(any());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(presence.activeUserIds(20L)).containsExactly(11L);
        verify(connections).close("ws-both");
        verify(connections).close("ws-live");
        verify(connections).close("ws-chat");
        verify(connections, never()).close("ws-other");
        verify(publisher, times(1)).publish(
                eq(20L),
                eq(GroupLiveEventType.PRESENCE_LEFT),
                eq(occurredAt),
                eq(new GroupLivePayloads.PresenceLeft(10L)));
    }

    @Test
    void Rollback이면_연결과_Presence를_유지한다() {
        GroupPresenceService presence = new GroupPresenceService();
        presence.register("ws-1", "http-1", "live-1", 10L, 20L);
        GroupLiveEventPublisher publisher = mock(GroupLiveEventPublisher.class);
        ActiveWebSocketConnectionRegistry connections = mock(ActiveWebSocketConnectionRegistry.class);
        GroupPresenceRevocationService service = new GroupPresenceRevocationService(
                presence, publisher, connections);
        beginTransaction();

        service.revokeAfterCommit(10L, Instant.parse("2026-11-19T00:00:00Z"));
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(presence.activeUserIds(20L)).containsExactly(10L);
        verify(connections, never()).close(any());
        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
