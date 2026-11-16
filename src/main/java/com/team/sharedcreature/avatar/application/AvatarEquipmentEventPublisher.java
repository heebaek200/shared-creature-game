package com.team.sharedcreature.avatar.application;

/**
 * 아바타 장착, 해제 이벤트를 Commit 이후 발행하도록 요청하는 경계입니다.
 */
public interface AvatarEquipmentEventPublisher {

    /**
     * 현재 트랜잭션이 정상적으로 Commit된 이후의 이벤트 발행을 예약합니다.
     *
     * @param event 장착 또는 해제된 슬롯의 변경 정보
     */
    // 롤백된 변경이 다른 그룹원에게 전달되지 않도록 구현해야 합니다.
    void publishAfterCommit(AvatarEquipmentChangedEvent event);
}
