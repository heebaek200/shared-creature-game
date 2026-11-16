package com.team.sharedcreature.quest.application;

/** 기여도 변경을 현재 트랜잭션 Commit 이후에 노출하는 발행 경계입니다. */
public interface ContributionUpdateEventPublisher {

    /** Commit 성공 후 내부 기여도 이벤트를 발행하도록 예약합니다. */
    void publishAfterCommit(ContributionUpdatedEvent event);
}
