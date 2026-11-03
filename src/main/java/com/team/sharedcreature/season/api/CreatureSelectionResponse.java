package com.team.sharedcreature.season.api;

import com.team.sharedcreature.season.domain.GroupSeason;

import java.time.Instant;

/** 관리자 선택 또는 무작위 확정 결과를 표현합니다. */
public record CreatureSelectionResponse(
        Long speciesId,
        String speciesName,
        String selectionSource,
        Instant selectedAt,
        Instant changeableUntil
) {
    /** 선택이 존재하는 그룹 시즌을 REST 응답으로 변환합니다. */
    public static CreatureSelectionResponse from(GroupSeason groupSeason) {
        return new CreatureSelectionResponse(
                groupSeason.getSelectedSpecies().getId(),
                groupSeason.getSelectedSpecies().getName(),
                groupSeason.getSelectionSource(),
                groupSeason.getSelectedAt(),
                groupSeason.getSelectionDeadlineAt()
        );
    }
}
