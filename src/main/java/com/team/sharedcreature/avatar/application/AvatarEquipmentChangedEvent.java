package com.team.sharedcreature.avatar.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.sharedcreature.avatar.api.AvatarResponse;

import java.time.Instant;

/**
 * Commit 이후 실시간 계층에 전달할 아바타 장착 변경 사실입니다.
 *
 * @param groupId 변경 사실을 전달할 그룹 ID
 * @param userId 아바타를 변경한 사용자 ID
 * @param slot 변경한 슬롯
 * @param equippedItem 변경 후 장착 아이템, 해제하면 null
 * @param occurredAt 변경 시각
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AvatarEquipmentChangedEvent(
        Long groupId,
        Long userId,
        String slot,
        AvatarResponse.Equipment equippedItem,
        Instant occurredAt
) {
    // 엔티티 대신 변경 당시의 응답 데이터를 담아 트랜잭션 밖에서도 사용합니다.
}