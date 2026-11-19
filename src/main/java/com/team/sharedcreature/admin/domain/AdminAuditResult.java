package com.team.sharedcreature.admin.domain;

/** 관리자 감사 로그의 처리 결과를 기존 DB 문자열로 저장합니다. */
public enum AdminAuditResult {
    SUCCEEDED,
    FAILED,
    SUCCESS // 기존 시드에 저장된 성공 코드입니다.
}
