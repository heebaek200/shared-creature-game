package com.team.sharedcreature.payment.domain;

/**
 * 한 번의 PG 호출 준비 상태와 확인된 결과를 나타냅니다.
 *
 * Enum 이름은 기존 result_type 컬럼의 저장 문자열을 유지합니다.
 */
public enum PgApiResultType {

    // 호출 준비 이력을 저장했으며 결과는 아직 기록하지 않은 상태입니다.
    STARTED,

    // PG 취소 성공을 확인한 결과입니다.
    SUCCESS,

    // PG 취소 실패가 명확히 확정된 결과입니다.
    DEFINITIVE_FAILURE,

    // PG에서 취소를 아직 처리 중임을 확인한 결과입니다.
    PENDING,

    // 통신 장애나 확인 근거 부족으로 결과를 확정하지 못한 상태입니다.
    UNKNOWN
}