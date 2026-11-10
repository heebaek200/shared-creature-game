package com.team.sharedcreature.season.api;

import com.team.sharedcreature.season.domain.Season;

import java.time.Instant;

/** 현재 시즌의 공개 Snapshot을 표현합니다. */
public record SeasonResponse(
        Long seasonId,
        Integer seasonNumber,
        String status,
        Instant startAt,
        Instant endAt
) {
    /** 시즌 엔티티를 REST 응답으로 변환합니다. */
    public static SeasonResponse from(Season season) {
        return new SeasonResponse(
                season.getId(),
                season.getSeasonNumber(),
                season.getStatus(),
                season.getStartAt(),
                season.getEndAt()
        );
    }
}
