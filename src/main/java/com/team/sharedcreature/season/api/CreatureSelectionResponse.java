package com.team.sharedcreature.season.api;

import com.team.sharedcreature.season.domain.GroupSeason;

import java.time.Instant;

/** 관리자가 확정한 크리처 종 선택 결과를 표현합니다. */
public record CreatureSelectionResponse(
        Long groupId,
        Long seasonId,
        Long creatureSpeciesId,
        Long selectedByUserId,
        Instant selectedAt,
        Instant selectionDeadlineAt
) {
    /** 관리자 선택이 존재하는 그룹 시즌을 REST 응답으로 변환합니다. */
    public static CreatureSelectionResponse fromAdminSelection(GroupSeason groupSeason) {
        if (groupSeason.getSelectedByUser() == null) {
            throw new IllegalArgumentException("관리자 선택 사용자 정보가 필요합니다.");
        }
        return new CreatureSelectionResponse(
                groupSeason.getGroup().getId(),
                groupSeason.getSeason().getId(),
                groupSeason.getSelectedSpecies().getId(),
                groupSeason.getSelectedByUser().getId(),
                groupSeason.getSelectedAt(),
                groupSeason.getSelectionDeadlineAt()
        );
    }
}
