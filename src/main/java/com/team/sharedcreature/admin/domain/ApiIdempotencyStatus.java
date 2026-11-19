package com.team.sharedcreature.admin.domain;

/** 멱등성 요청의 처리 상태를 기존 DB 문자열로 저장합니다. */
public enum ApiIdempotencyStatus {
    PROCESSING,
    COMPLETED
}
