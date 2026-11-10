package com.team.sharedcreature.season.api;

import com.team.sharedcreature.season.domain.CreatureSpecies;

/** 그룹 구성원이 조회할 수 있는 활성 크리처 종을 표현합니다. */
public record CreatureSpeciesResponse(
        Long creatureSpeciesId,
        String code,
        String name,
        String assetKey,
        Integer displayOrder
) {
    /** 활성 종 엔티티를 REST 응답으로 변환합니다. */
    public static CreatureSpeciesResponse from(CreatureSpecies species) {
        return new CreatureSpeciesResponse(
                species.getId(),
                species.getCode(),
                species.getName(),
                species.getAssetKey(),
                species.getDisplayOrder()
        );
    }
}
