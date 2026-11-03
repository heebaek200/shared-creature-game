package com.team.sharedcreature._core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

/**
 * REST 성공 응답의 data와 meta 형식을 통일합니다.
 *
 * @param data 실제 응답 데이터
 * @param meta 공통 응답 메타데이터
 * @param <T> 응답 데이터 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, ApiMeta meta) {

    /**
     * 페이지 메타데이터가 없는 단건 성공 응답을 생성합니다.
     *
     * @param data 실제 응답 데이터
     * @param <T> 응답 데이터 타입
     * @return 공통 성공 응답
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null);
    }

    /** Spring Data 페이지의 항목과 페이지 메타데이터를 공통 형식으로 변환합니다. */
    public static <T> ApiResponse<java.util.List<T>> page(Page<T> page) {
        return new ApiResponse<>(
                page.getContent(),
                new ApiMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages())
        );
    }
}
