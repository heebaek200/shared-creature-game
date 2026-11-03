package com.team.sharedcreature._core.api;

import java.time.Clock;
import java.time.Instant;

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
        return new ApiResponse<>(data, new ApiMeta(Instant.now(clock)));
    }
}
