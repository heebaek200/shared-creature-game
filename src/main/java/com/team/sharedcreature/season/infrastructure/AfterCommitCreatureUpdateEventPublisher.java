package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.application.CreatureUpdateEventPublisher;
import com.team.sharedcreature.season.application.CreatureUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Spring 트랜잭션 Commit 성공 후에만 크리처 변경 이벤트를 발행합니다. */
@Component
public class AfterCommitCreatureUpdateEventPublisher implements CreatureUpdateEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /** Spring 애플리케이션 이벤트 발행기를 주입받습니다. */
    public AfterCommitCreatureUpdateEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Rollback 시 이벤트가 유출되지 않도록 현재 트랜잭션에 afterCommit Callback을 등록합니다. */
    @Override
    public void publishAfterCommit(CreatureUpdatedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Creature events require an active transaction synchronization.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(event);
            }
        });
    }
}
