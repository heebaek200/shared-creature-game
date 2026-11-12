package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.application.AvatarEquipmentChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Spring 트랜잭션 콜백으로 이벤트 발행 시점을 검증합니다. */
class AfterCommitAvatarEquipmentEventPublisherTest {

    private final List<Object> receivedEvents = new ArrayList<>();
    private final AfterCommitAvatarEquipmentEventPublisher publisher =
            new AfterCommitAvatarEquipmentEventPublisher(event -> receivedEvents.add(event));
    private final TransactionTemplate transaction = new TransactionTemplate(new TestTransactionManager());
    private final AvatarEquipmentChangedEvent event = new AvatarEquipmentChangedEvent(
            1L, 2L, "HEAD", null, Instant.parse("2026-11-11T00:00:00Z"));

    @Test
    void Commit_이후에만_이벤트를_한번_발행한다() {
        transaction.executeWithoutResult(status -> {
            publisher.publishAfterCommit(event);
            // 예약과 실제 발행을 구분해 미확정 변경이 노출되지 않는지 확인합니다.
            assertThat(receivedEvents).isEmpty();
        });

        assertThat(receivedEvents).containsExactly(event);
    }

    @Test
    void Rollback하면_이벤트를_발행하지_않는다() {
        transaction.executeWithoutResult(status -> {
            publisher.publishAfterCommit(event);
            status.setRollbackOnly();
        });

        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void 트랜잭션_밖에서는_예약을_거부한다() {
        assertThatThrownBy(() -> publisher.publishAfterCommit(event))
                .isInstanceOf(IllegalStateException.class);
        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void 실제_트랜잭션_없는_동기화만으로는_예약할_수_없다() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> publisher.publishAfterCommit(event))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void 전파_실패가_이미_확정된_장착의_재시도를_유발하지_않는다() {
        var failingPublisher = new AfterCommitAvatarEquipmentEventPublisher(value -> {
            throw new IllegalStateException("의도한 전파 실패");
        });

        // Commit 이후 전파 오류가 바깥의 재시도 Service까지 전달되면 안 됩니다.
        assertThatCode(() -> transaction.executeWithoutResult(
                status -> failingPublisher.publishAfterCommit(event)))
                .doesNotThrowAnyException();
    }

    /** 실제 DB 없이 Spring 트랜잭션 생명주기와 완료 콜백을 실행합니다. */
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 실제 DB 작업은 이 테스트의 검증 대상이 아닙니다.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 상위 클래스가 Commit 후 콜백을 실행합니다.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 상위 클래스가 Rollback 완료 처리를 실행합니다.
        }
    }
}
