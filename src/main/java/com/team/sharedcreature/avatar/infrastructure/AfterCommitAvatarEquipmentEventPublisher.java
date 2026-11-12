package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.application.AvatarEquipmentChangedEvent;
import com.team.sharedcreature.avatar.application.AvatarEquipmentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 아바타 변경 트랜잭션이 Commit된 후 내부 이벤트를 발행합니다.
 *
 * 롤백된 변경은 발행하지 않습니다.
 */
@Component
public class AfterCommitAvatarEquipmentEventPublisher
        implements AvatarEquipmentEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(AfterCommitAvatarEquipmentEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Spring 내부 이벤트 발행기를 주입받습니다.
     *
     * @param eventPublisher Spring 내부 이벤트 발행기
     */
    public AfterCommitAvatarEquipmentEventPublisher(
            ApplicationEventPublisher eventPublisher
    ) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 현재 트랜잭션의 Commit 성공 이후 이벤트 발행을 예약합니다.
     *
     * @param event 아바타 장착·해제 변경 정보
     * @throws IllegalStateException 활성 트랜잭션이나 동기화 기능이 없는 경우
     */
    @Override
    public void publishAfterCommit(AvatarEquipmentChangedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "아바타 변경 이벤트는 활성 트랜잭션 안에서 예약해야 합니다."
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    /**
                     * DB Commit이 성공한 경우에만 내부 이벤트를 발행합니다.
                     */
                    @Override
                    public void afterCommit() {
                        try {
                            eventPublisher.publishEvent(event);
                        } catch (RuntimeException exception) {
                            // DB 변경은 이미 확정됐으므로 전파 실패로 장착을 재시도하지 않습니다.
                            log.error(
                                    "아바타 변경 이벤트 전파 실패. groupId={}, userId={}, slot={}",
                                    event.groupId(),
                                    event.userId(),
                                    event.slot(),
                                    exception
                            );
                        }
                    }
                }
        );
    }
}