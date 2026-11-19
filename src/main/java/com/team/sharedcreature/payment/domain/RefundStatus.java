package com.team.sharedcreature.payment.domain;

/**
 * 환불 요청의 심사 및 PG 취소 처리 상태입니다.
 *
 * Enum 이름은 기존 DB 저장값과 API 문자열을 그대로 유지합니다.
 */
public enum RefundStatus {

    // 사용자가 요청했으며 관리자 심사를 기다리는 상태입니다.
    REQUESTED,

    // 관리자가 사유를 기록하고 환불을 거절한 상태입니다.
    REJECTED,

    // 취소 처리권을 선점했거나 PG에서 취소를 처리 중인 상태입니다.
    CANCEL_PENDING,

    // 통신 장애 등으로 취소 결과를 확정하지 못한 상태입니다.
    CANCEL_UNKNOWN,

    // PG 취소 성공이 확정되어 아이템 회수까지 반영된 상태입니다.
    COMPLETED,

    // PG 취소 실패가 명확히 확정되어 아이템 보유를 유지하는 상태입니다.
    FAILED
}
