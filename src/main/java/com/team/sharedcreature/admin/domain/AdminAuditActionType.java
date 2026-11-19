package com.team.sharedcreature.admin.domain;

/** 관리자 감사 로그에 기록하는 작업 유형입니다. */
public enum AdminAuditActionType {
    DISMISS_REPORT,
    REMOVE_REPORTED_MEMBER,
    TRANSFER_PRIMARY_AS_SUPER_ADMIN,
    RESOLVE_REPORT // 기존 시드에 기록된 신고 처리 유형입니다.
}
