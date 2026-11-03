package com.team.sharedcreature._core.api;

/**
 * 입력 필드별 검증 실패 내용을 전달합니다.
 *
 * @param field 실패한 필드명
 * @param reason 안전한 실패 사유
 */
public record FieldErrorResponse(String field, String reason) {
}
