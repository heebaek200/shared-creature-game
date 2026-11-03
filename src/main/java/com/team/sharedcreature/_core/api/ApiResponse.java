package com.team.sharedcreature._core.api;

import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.Page;

/**
 * REST 성공 응답의 data와 meta 형식을 통일합니다.
 *
 * @param data 실제 응답 데이터
 * @param meta 공통 응답 메타데이터
 * @param <T> 응답 데이터 타입
 */
public record ApiResponse<T>(T data, ApiMeta meta) {

    /**
     * 시스템 UTC 시각으로 성공 응답을 생성합니다.
     *
     * @param data 실제 응답 데이터
     * @param <T> 응답 데이터 타입
     * @return 공통 성공 응답
     */
    public static <T> ApiResponse<T> success(T data) {
        return success(data, Clock.systemUTC());
    }

    /**
     * 테스트 가능한 Clock을 사용해 성공 응답을 생성합니다.
     *
     * @param data 실제 응답 데이터
     * @param clock 서버 시각을 제공하는 Clock
     * @param <T> 응답 데이터 타입
     * @return 공통 성공 응답
     */
    public static <T> ApiResponse<T> success(T data, Clock clock) {
        return new ApiResponse<>(data, ApiMeta.common(Instant.now(clock)));
    }

    /** Spring Data 페이지의 항목과 페이지 메타데이터를 공통 형식으로 변환합니다. */
    public static <T> ApiResponse<java.util.List<T>> page(Page<T> page) {
        return new ApiResponse<>(
                page.getContent(),
                ApiMeta.page(
                        Instant.now(Clock.systemUTC()),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages()
                )
        );
    }
}
