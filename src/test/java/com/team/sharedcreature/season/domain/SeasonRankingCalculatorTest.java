
package com.team.sharedcreature.season.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.team.sharedcreature.season.domain.SeasonRankingCalculator.*;

/**
 * 시즌 종료 Snapshot의 그룹·개인 순위 계산을 검증합니다.
 * 동점, 0점, NULL, 중복 데이터의 경계를 테스트합니다.
 * DB 연결 없이 동일 입력의 결정적인 결과를 보장합니다.
 */
class SeasonRankingCalculatorTest {

    private static final Instant T0 =
            Instant.parse("2026-11-02T00:00:00Z");

    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);

    /**
     * 그룹 점수가 높은 순서로 정렬되는지 확인합니다.
     * 입력 목록의 기존 순서는 무시해야 합니다.
     * 순위 번호는 1부터 연속으로 부여합니다.
     */
    @Test
    void groupsAreSortedByScoreDescending() {
        List<GroupRank> ranks = rankGroups(List.of(
                new GroupEntry(3, 10, T0, T0),
                new GroupEntry(2, 30, T2, T0),
                new GroupEntry(1, 20, T1, T0)
        ));

        assertEquals(List.of(2L, 1L, 3L),
                ranks.stream().map(GroupRank::groupId).toList());

        assertEquals(List.of(1, 2, 3),
                ranks.stream().map(GroupRank::rankNumber).toList());
    }

    /**
     * 같은 그룹 점수에서는 도달 시각을 우선합니다.
     * 도달 시각까지 같으면 그룹 ID 오름차순입니다.
     * DB 조회 순서와 관계없이 같은 랭킹을 반환합니다.
     */
    @Test
    void groupTiesUseTimeThenId() {
        List<GroupRank> ranks = rankGroups(List.of(
                new GroupEntry(8, 50, T1, T0),
                new GroupEntry(4, 50, T0, T0),
                new GroupEntry(3, 50, T0, T0)
        ));

        assertEquals(List.of(3L, 4L, 8L),
                ranks.stream().map(GroupRank::groupId).toList());
    }

    /**
     * 0점 그룹은 점수 집계 생성 시각을 사용합니다.
     * NULL을 Snapshot에 그대로 저장하지 않아야 합니다.
     * 생성 시각이 빠른 그룹이 먼저 정렬됩니다.
     */
    @Test
    void zeroScoreUsesCreatedAt() {
        List<GroupRank> ranks = rankGroups(List.of(
                new GroupEntry(5, 0, null, T2),
                new GroupEntry(2, 0, null, T0),
                new GroupEntry(3, 0, null, T1)
        ));

        assertEquals(List.of(2L, 3L, 5L),
                ranks.stream().map(GroupRank::groupId).toList());

        assertEquals(T0, ranks.get(0).scoreReachedAt());
        assertEquals(T1, ranks.get(1).scoreReachedAt());
        assertEquals(T2, ranks.get(2).scoreReachedAt());
    }

    /**
     * 0점 도달 시각도 같으면 그룹 ID를 기준으로 정렬합니다.
     * 동일한 시각에 초기화된 그룹의 순위가 안정적이어야 합니다.
     * 처리 순서에 따른 비결정적 결과를 방지합니다.
     */
    @Test
    void zeroScoreTiesUseGroupId() {
        List<GroupRank> ranks = rankGroups(List.of(
                new GroupEntry(9, 0, null, T0),
                new GroupEntry(3, 0, null, T0)
        ));

        assertEquals(List.of(3L, 9L),
                ranks.stream().map(GroupRank::groupId).toList());
    }

    /**
     * 양수 점수의 도달 시각이 없다면 정합성 오류입니다.
     * 종료 시각을 임의의 도달 시각으로 대체하지 않습니다.
     * 잘못된 데이터는 Snapshot 저장 전에 발견해야 합니다.
     */
    @Test
    void positiveScoreWithoutReachedAtIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                rankGroups(List.of(
                        new GroupEntry(1, 100, null, T0)
                ))
        );
    }

    /**
     * 그룹별 ID가 중복되면 정렬 전에 거절합니다.
     * UNIQUE(season_id, group_id) 위반을 조기에 확인합니다.
     * 임의로 중복 항목을 제거하지 않습니다.
     */
    @Test
    void duplicateGroupIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                rankGroups(List.of(
                        new GroupEntry(1, 10, T0, T0),
                        new GroupEntry(1, 20, T1, T0)
                ))
        );
    }

    /**
     * 개인 기여도도 점수, 시각, ID 순서로 정렬합니다.
     * 동일한 점수와 시각이라면 사용자 ID가 결정합니다.
     * 개인 순위는 그룹 시즌별로 1부터 시작합니다.
     */
    @Test
    void membersUseScoreTimeAndId() {
        List<MemberRank> ranks = rankMembers(List.of(
                new MemberEntry(9, 2, T0),
                new MemberEntry(3, 8, T1),
                new MemberEntry(4, 8, T1),
                new MemberEntry(2, 8, T0)
        ));

        assertEquals(List.of(2L, 3L, 4L, 9L),
                ranks.stream().map(MemberRank::userId).toList());

        assertEquals(List.of(1, 2, 3, 4),
                ranks.stream().map(MemberRank::rankNumber).toList());
    }

    /**
     * 개인 기여도의 도달 시각 NULL을 거절합니다.
     * Snapshot의 NOT NULL 조건을 미리 검증합니다.
     * 0점 개인 데이터도 시각을 임의 보정하지 않습니다.
     */
    @Test
    void memberWithoutReachedAtIsRejected() {
        assertThrows(NullPointerException.class, () ->
                rankMembers(List.of(
                        new MemberEntry(1, 0, null)
                ))
        );
    }

    /**
     * 같은 그룹 시즌에서 중복 사용자 ID를 거절합니다.
     * UNIQUE(group_season_id, user_id) 제약과 일치해야 합니다.
     * 중복 기여도를 합산하지 않습니다.
     */
    @Test
    void duplicateMemberIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                rankMembers(List.of(
                        new MemberEntry(1, 10, T0),
                        new MemberEntry(1, 20, T1)
                ))
        );
    }

    /**
     * 빈 목록도 정상적인 랭킹 입력으로 허용합니다.
     * 참여한 그룹이나 기여도가 없을 수 있습니다.
     * 빈 Snapshot 결과를 오류로 취급하지 않습니다.
     */
    @Test
    void emptyInputsReturnEmptyResults() {
        assertTrue(rankGroups(List.of()).isEmpty());
        assertTrue(rankMembers(List.of()).isEmpty());
    }
}
