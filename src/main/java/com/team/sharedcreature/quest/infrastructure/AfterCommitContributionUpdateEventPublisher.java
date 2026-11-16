package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.application.ContributionUpdateEventPublisher;
import com.team.sharedcreature.quest.application.ContributionUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 퀘스트 보상 트랜잭션 Commit 성공 후 기여도 변경 이벤트를 발행합니다. */
@Slf4j
@Component
public class AfterCommitContributionUpdateEventPublisher implements ContributionUpdateEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /** Spring 내부 이벤트 발행기를 주입받습니다. */
    public AfterCommitContributionUpdateEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Rollback된 기여도 변경이 전파되지 않도록 afterCommit Callback을 등록합니다. */
    @Override
    public void publishAfterCommit(ContributionUpdatedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("기여도 이벤트는 활성 트랜잭션 안에서 예약해야 합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    eventPublisher.publishEvent(event);
                } catch (RuntimeException exception) {
                    log.error(
                            "기여도 변경 이벤트 전파 실패. groupId={}, userId={}",
                            event.groupId(),
                            event.userId(),
                            exception
                    );
                }
            }
        });
    }
}
