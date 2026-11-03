package com.team.sharedcreature.season.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 부화 전 크리처 종 선택 입력입니다.
 *
 * @param creatureSpeciesId 선택할 활성 종 ID
 */
public record CreatureSelectionRequest(
        @NotNull @Positive Long creatureSpeciesId
) {
}
