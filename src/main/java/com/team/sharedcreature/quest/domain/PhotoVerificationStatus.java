package com.team.sharedcreature.quest.domain;

/** 사진 제출의 판정 상태입니다. 기존 DB·API의 상태 코드를 유지합니다. */
public enum PhotoVerificationStatus {
    PENDING,
    VALID,
    INVALID
}
