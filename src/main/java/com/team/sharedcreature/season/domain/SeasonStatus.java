package com.team.sharedcreature.season.domain;

/** 시즌 진행 상태입니다. DB·API의 기존 상태 이름을 유지합니다. */
public enum SeasonStatus {
    SCHEDULED,
    ACTIVE,
    CLOSING,
    ENDED
}
