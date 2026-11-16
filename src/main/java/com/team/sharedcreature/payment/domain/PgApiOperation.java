package com.team.sharedcreature.payment.domain;

/**
 * PG 호출 이력에 기록하는 내부 작업 유형입니다.
 *
 * Enum 이름은 기존 operation 컬럼의 저장 문자열을 유지합니다.
 */
public enum PgApiOperation {

    // 관리자 승인 후 실제 결제 취소를 요청하는 작업입니다.
    CANCELLATION,

    // 취소를 재요청하지 않고 기존 취소 결과를 확인하는 조회 작업입니다.
    CANCELLATION_LOOKUP
}