package com.team.sharedcreature._core.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.sharedcreature.avatar.api.AvatarResponse;
import com.team.sharedcreature.season.api.CreatureHomeResponse;

/** 그룹 실시간 이벤트별 Payload 계약을 모읍니다. */
public final class GroupLivePayloads {

    private GroupLivePayloads() {
    }

    /** 접속한 구성원의 화면 표시 정보입니다. */
    public record PresenceJoined(
            Long userId,
            String nickname,
            Integer rank,
            long contributionScore,
            CreatureHomeResponse.EquipmentSlotsResponse equipments
    ) {
    }

    /** 퇴장한 구성원을 식별합니다. */
    public record PresenceLeft(Long userId) {
    }

    /** 아바타 슬롯의 변경 후 상태입니다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AvatarEquipmentChanged(
            Long userId,
            String slot,
            AvatarResponse.Equipment equippedItem
    ) {
    }

    /** 공동 크리처의 변경 후 상태입니다. */
    public record CreatureUpdated(
            Long seasonId,
            Long groupCreatureId,
            long totalExperience,
            String growthStage,
            Long creatureSpeciesId,
            java.time.Instant lastUpdatedAt
    ) {
    }

    /** 퀘스트 보상으로 변경된 개인·그룹 기여 상태입니다. */
    public record ContributionUpdated(
            Long userId,
            String nickname,
            long contributionDelta,
            long contributionScore,
            long creatureExperienceDelta,
            long groupScore
    ) {
    }
}
