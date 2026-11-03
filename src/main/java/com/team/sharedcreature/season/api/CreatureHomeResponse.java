package com.team.sharedcreature.season.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 크리처 홈을 복원하는 REST Snapshot 전체를 표현합니다. */
public record CreatureHomeResponse(
        Long groupId,
        SeasonResponse season,
        CreatureResponse creature,
        long todayExperience,
        List<ContributionActivityResponse> recentContributions,
        List<PresentAvatarResponse> presentAvatars,
        UUID streamInstanceId,
        long sequence
) {
    /** 공동 크리처의 현재 종·성장·경험치 상태입니다. */
    public record CreatureResponse(
            Long speciesId,
            String speciesName,
            String stage,
            Instant hatchesAt,
            long experience,
            long currentStageThreshold,
            Long nextStageThreshold
    ) {
    }

    /** 최근 퀘스트 완료로 확정된 그룹 기여 활동입니다. */
    public record ContributionActivityResponse(
            Long userId,
            String nickname,
            long experience,
            long contribution,
            Instant occurredAt
    ) {
    }

    /** 현재 접속 중인 그룹원의 아바타 Snapshot입니다. */
    public record PresentAvatarResponse(
            Long userId,
            String nickname,
            long contribution,
            int rank,
            boolean me,
            List<EquippedItemResponse> equipments
    ) {
    }

    /** 접속 아바타에 장착된 슬롯별 상품 외형입니다. */
    public record EquippedItemResponse(
            String slot,
            Long itemId,
            String itemName,
            String assetKey
    ) {
    }
}
