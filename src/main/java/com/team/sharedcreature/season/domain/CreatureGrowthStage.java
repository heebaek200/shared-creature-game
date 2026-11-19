package com.team.sharedcreature.season.domain;

/** 공동 크리처의 성장 단계입니다. DB·응답·이벤트의 기존 코드를 유지합니다. */
public enum CreatureGrowthStage {
    EGG,
    HATCHLING,
    GROWING,
    MATURE,
    COMPLETE
}
