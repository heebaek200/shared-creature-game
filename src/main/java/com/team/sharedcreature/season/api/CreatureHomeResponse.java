package com.team.sharedcreature.season.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 크리처 홈을 복원하는 REST Snapshot 전체를 표현합니다. */
public record CreatureHomeResponse(
        Long groupId,
        String groupName,
        SeasonResponse season,
        CreatureResponse creature,
        long todayExperience,
        List<OnlineMemberResponse> onlineMembers,
        List<ContributionActivityResponse> recentContributions,
        Instant snapshotAt,
        UUID streamInstanceId,
        long sequence
) {
    /** 공동 크리처의 현재 종·성장·경험치 상태입니다. */
    public record CreatureResponse(
            Long groupCreatureId,
            Long creatureSpeciesId,
            String growthStage,
            long totalExperience,
            Long nextStageAtExperience,
            Instant selectionDeadlineAt
    ) {
    }

    /** 최근 퀘스트 완료로 크리처가 획득한 경험치 활동입니다. */
    public record ContributionActivityResponse(
            Long userId,
            String nickname,
            long amount,
            Instant occurredAt
    ) {
    }

    /** 현재 접속 중인 그룹원의 순위·기여도·아바타 Snapshot입니다. */
    public record OnlineMemberResponse(
            Long userId,
            String nickname,
            Integer rank,
            long contributionScore,
            AvatarResponse avatar
    ) {
    }

    /** 온라인 구성원의 장착 아바타 정보입니다. */
    public record AvatarResponse(EquipmentSlotsResponse equipments) {
    }

    /** 네 장착 슬롯을 항상 노출하고 미장착 슬롯은 null로 표현합니다. */
    public record EquipmentSlotsResponse(
            @JsonProperty("HEAD") EquippedItemResponse head,
            @JsonProperty("FACE") EquippedItemResponse face,
            @JsonProperty("BODY") EquippedItemResponse body,
            @JsonProperty("ACCESSORY") EquippedItemResponse accessory
    ) {
    }

    /** 접속 아바타에 장착된 보유 아이템과 상품 외형입니다. */
    public record EquippedItemResponse(
            Long ownedItemId,
            Long shopItemId,
            String name,
            String assetKey
    ) {
    }
}
