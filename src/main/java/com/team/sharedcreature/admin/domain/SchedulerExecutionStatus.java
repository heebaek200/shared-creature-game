package com.team.sharedcreature.admin.domain;

/** Scheduler 실행 상태를 기존 DB 문자열로 저장합니다. */
public enum SchedulerExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
