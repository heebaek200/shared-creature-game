package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.GroupCreature;

import java.time.Instant;

/** Commit 이후 실시간 계층에 전달할 크리처 변경 사실입니다. */
public record CreatureUpdatedEvent(
        Long groupId,
        Long seasonId,
        Long groupCreatureId,
        String stage,
        long experience,
        Long creatureSpeciesId,
        Instant occurredAt
) {
    /** 변경 후 크리처와 선택 종을 외부 Entity 참조 없이 복사합니다. */
    public static CreatureUpdatedEvent from(
            GroupCreature creature,
            Instant occurredAt
    ) {
        var groupSeason = creature.getGroupSeason();
        var visibleSpecies = creature.getSpecies() != null
                ? creature.getSpecies()
                : groupSeason.getSelectedSpecies();
        return new CreatureUpdatedEvent(
                groupSeason.getGroup().getId(),
                groupSeason.getSeason().getId(),
                creature.getId(),
                creature.getGrowthStage(),
                creature.getTotalExperience(),
                visibleSpecies == null ? null : visibleSpecies.getId(),
                occurredAt
        );
    }
}
