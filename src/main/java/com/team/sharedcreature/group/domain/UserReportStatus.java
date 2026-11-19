package com.team.sharedcreature.group.domain;

/** 신고의 접수 및 심사 결과입니다. 기존 DB·API의 상태 코드를 유지합니다. */
public enum UserReportStatus {
    RECEIVED,
    RESOLVED,
    REJECTED
}
