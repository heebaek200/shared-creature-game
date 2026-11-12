package com.team.sharedcreature.ranking.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 개인·그룹 시즌 랭킹의 응답 DTO를 정의합니다.
 * 랭킹 전용 메타데이터를 사용하여 기존 API 응답에 영향을 주지 않습니다.
 */
public final class RankingResponse {

    // 응답 DTO를 모아 두는 클래스이므로 직접 생성하지 않습니다.
    private RankingResponse() {
    }

    /**
     * 그룹 내 개인 시즌 랭킹 항목입니다.
     * 탈퇴·강퇴된 사용자의 기록도 참여 상태와 함께 반환합니다.
     */
    public record MemberDto(
            int rank,
            Long userId,
            String nickname,
            Long contributionScore,
            Instant scoreReachedAt,
            String participantStatus
    ) {
    }

    /**
     * 전체 그룹 시즌 랭킹 항목입니다.
     */
    public record GroupDto(
            int rank,
            Long groupId,
            String groupName,
            Long score,
            Instant scoreReachedAt
    ) {
    }

    /**
     * 현재 사용자 또는 현재 소속 그룹의 전체 순위입니다.
     * 순위가 없는 경우에도 rank 키를 유지하고 null을 반환합니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CurrentSubject(
            Long subjectId,
            Integer rank,
            Long score
    ) {
    }

    /**
     * 페이지 정보와 현재 조회 대상의 순위를 반환합니다.
     * 대상이 없더라도 currentSubject 키는 null로 유지합니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Meta(
            int page,
            int size,
            long totalElements,
            int totalPages,
            CurrentSubject currentSubject
    ) {
    }

    /**
     * 랭킹 목록과 전용 메타데이터를 묶는 성공 응답입니다.
     *
     * @param data 현재 페이지의 랭킹 목록
     * @param meta 페이지 정보와 현재 조회 대상의 순위
     * @param <T> 개인 또는 그룹 랭킹 항목 타입
     */
    public record PageDto<T>(
            List<T> data,
            Meta meta
    ) {
    }
}