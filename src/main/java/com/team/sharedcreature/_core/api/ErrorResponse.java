package com.team.sharedcreature._core.api;

import java.time.Instant;
import java.util.List;

/**
 * 내부 예외를 노출하지 않는 공통 REST 오류 응답입니다.
 *
 * @param code 클라이언트 분기용 오류 코드
 * @param message 사용자에게 표시할 안전한 메시지
 * @param fieldErrors 필드별 검증 오류
 * @param traceId X-Request-Id와 동일한 추적 ID
 * @param timestamp 오류 응답 생성 UTC 시각
 */
public record ErrorResponse(
        String code,
        String message,
        List<FieldErrorResponse> fieldErrors,
        String traceId,
        Instant timestamp
) {
}
