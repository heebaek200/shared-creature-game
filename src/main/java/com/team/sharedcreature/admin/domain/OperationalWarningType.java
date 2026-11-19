package com.team.sharedcreature.admin.domain;

/** 운영 상태 검사에서 발견하는 경고 유형입니다. */
public enum OperationalWarningType {
    SCHEDULER_FAILED,
    SEASON_STATE_INVALID,
    TEMPLATE_SHORTAGE,
    QUEST_PERIOD_FAILED,
    MEMBER_COUNT_MISMATCH,
    PAYMENT_UNCERTAIN,
    REFUND_UNCERTAIN,
    PHOTO_DELETION_FAILED
}
