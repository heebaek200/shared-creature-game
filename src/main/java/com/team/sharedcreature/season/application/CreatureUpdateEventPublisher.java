package com.team.sharedcreature.season.application;

/** 크리처 변경 이벤트가 데이터베이스 Commit 이후에만 노출되도록 하는 발행 경계입니다. */
public interface CreatureUpdateEventPublisher {

    /** 현재 트랜잭션 Commit 이후 이벤트를 발행하도록 예약합니다. */
    void publishAfterCommit(CreatureUpdatedEvent event);
}
