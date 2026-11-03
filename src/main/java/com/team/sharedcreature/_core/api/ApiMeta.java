package com.team.sharedcreature._core.api;

/**
 * 페이징 성공 응답의 목록 위치와 전체 크기를 제공합니다.
 *
 * @param page 현재 페이지 번호
 * @param size 페이지 크기
 * @param totalElements 전체 항목 수
 * @param totalPages 전체 페이지 수
 */
public record ApiMeta(
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
