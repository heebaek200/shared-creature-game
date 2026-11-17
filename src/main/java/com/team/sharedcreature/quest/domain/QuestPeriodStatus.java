package com.team.sharedcreature.quest.domain;

/** 퀘스트 기간의 배정·운영 상태입니다. 기존 DB 코드를 유지합니다. */
public enum QuestPeriodStatus {
    PENDING,
    ACTIVE,
    FAILED,
    CLOSED
}
