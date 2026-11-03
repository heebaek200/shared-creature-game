package com.team.sharedcreature._core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 모든 성공 응답에 서버 처리 시각을 제공합니다.
 *
 * @param serverTime 응답을 생성한 UTC 시각
 * @param page 현재 페이지 번호
 * @param size 페이지 크기
 * @param totalElements 전체 항목 수
 * @param totalPages 전체 페이지 수
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiMeta(
        Instant serverTime,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** 페이지 정보가 없는 일반 응답 메타데이터를 생성합니다. */
    public static ApiMeta common(Instant serverTime) {
        return new ApiMeta(serverTime, null, null, null, null);
    }

    /** 목록 응답의 페이지 메타데이터를 생성합니다. */
    public static ApiMeta page(Instant serverTime, int page, int size, long totalElements, int totalPages) {
        return new ApiMeta(serverTime, page, size, totalElements, totalPages);
    }
}
