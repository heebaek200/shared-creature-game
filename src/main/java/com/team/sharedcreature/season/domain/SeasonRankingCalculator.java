
package com.team.sharedcreature.season.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 종료 시즌의 그룹 및 개인 랭킹 순서를 계산함.
 * 점수 내림차순, 도달 시각 오름차순, ID 오른차순을 적용함.
 * 실제 DB 조회와 Snapshot 저장은 별도의 Service에서 수행함.
 */
public class SeasonRankingCalculator {

    /** 정적 계산 메서드만 제공하는 유틸리티 클래스 */
    private SeasonRankingCalculator() {}

    /**
     * 그룹 랭킹 계산의 입력값 레코드.
     * scoreReachedAt : 0점일 때만 NULL을 허용함
     * scoreCreatedAt : SeasonGroupScore의 생성 시각
     */
    public record GroupEntry(
            long groupId,
            long score,
            Instant scoreReachedAt,
            Instant scoreCreatedAt
    ) {}

    /**
     * 순위가 확정된 그룹 점수 레코드.
     * rankNumber : 시즌 전체에서 1부터 시작
     * Snapshot 저장 전 순위 계산 결과로 사용함
     */
    public record GroupRank(
            int rankNumber,
            long groupId,
            long score,
            Instant scoreReachedAt
    ) {}

    /**
     * 개인 랭킹 계산의 입력값 레코드.
     * 하나의 그룹 시즌에 속하는 기여도만 포함해야 함.
     * scoreReachedAt은 필수 값.
     */
    public record MemberEntry(
            long userId,
            long contributionScore,
            Instant scoreReachedAt
    ) {}

    /**
     * 순위가 확정된 개인 기여도 레코드.
     * rankNumber는 그룹 시즌별로 1부터 시작
     * 탈퇴 여부와 닉네임 Snapshot은 저장 단계에서 반영.
     */
    public record MemberRank(
            int rankNumber,
            long userId,
            long contributionScore,
            Instant scoreReachedAt
    ) {}

    /**
     * 그룹 시즌 점수를 정렬하고 순위 번호를 부여함.
     * 0점 그룹의 도달 시각이 없으면 점수 행 생성 시각을 사용함.
     * 같은 그룹 ID가 중복되거나 점수 데이터가 잘못되면 거절함.
     */
    public static List<GroupRank> rankGroups(List<GroupEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        List<GroupEntry> sorted = new ArrayList<>(entries);
        Set<Long> ids = new HashSet<>();

        // 정렬 전 데이터 정합성을 검사
        for (GroupEntry entry : sorted) {
            Objects.requireNonNull(entry, "group entry");

            if (entry.groupId() <= 0 || entry.score() < 0) {
                throw new IllegalArgumentException("잘못된 그룹 점수입니다.");
            }

            if (!ids.add(entry.groupId())) {
                throw new IllegalArgumentException("중복된 그룹 ID입니다.");
            }

            groupReachedAt(entry);
        }

        // 점수 DESC, 도달 시간 ASC, 그룹ID ASC
        sorted.sort(
                Comparator
                        .comparingLong(GroupEntry::score)
                        .reversed()
                        .thenComparing(SeasonRankingCalculator::groupReachedAt)
                        .thenComparingLong(GroupEntry::groupId)
        );

        List<GroupRank> result = new ArrayList<>();

        // UNIQUE(rank_number)에 사용할 연속 순위를 부여
        for (int i = 0; i < sorted.size(); i++) {
            GroupEntry entry = sorted.get(i);

            result.add(new GroupRank(
                    i + 1,
                    entry.groupId(),
                    entry.score(),
                    groupReachedAt(entry)
            ));
        }

        return List.copyOf(result);
    }

    /**
     * 그룹 점수의 실제 순위 도달 시각을 반환함.
     * 0점 그룹은 최초 점수 행 생성 시각을 사용함.
     * 양수 점수의 도달 시각이 없으면 정합성 오류로 처리함.
     */
    private static Instant groupReachedAt(GroupEntry entry) {
        if (entry.scoreReachedAt() != null) {
            return entry.scoreReachedAt();
        }

        if (entry.score() == 0) {
            return Objects.requireNonNull(entry.scoreCreatedAt(), "0점 그룹의 생성 시각이 없습니다.");
        }

        throw new IllegalArgumentException("양수 그룹 점수의 도달 시각이 없습니다.");
    }

    /**
     * 그룹 안의 개인 기여도를 정렬하고 순위를 부여한다.
     * 점수 DESC, 도달 시각 ASC, 사용자ID ASC 순서.
     * 하나의 그룹 시즌에 해당하는 입력만 전달해야 한다.
     */
    public static List<MemberRank> rankMembers(List<MemberEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        List<MemberEntry> sorted = new ArrayList<>(entries);
        Set<Long> ids = new HashSet<>();

        // 개인 기여도의 필수 값과 중복 여부를 검사
        for (MemberEntry entry : sorted) {
            Objects.requireNonNull(entry, "member entry");

            if (entry.userId() <= 0 || entry.contributionScore() < 0) {
                throw new IllegalArgumentException("잘못된 개인 기여도입니다.");
            }

            Objects.requireNonNull(entry.scoreReachedAt(), "개인 기여도 도달 시각이 없습니다.");

            if (!ids.add(entry.userId())) {
                throw new IllegalArgumentException("중복된 사용자 ID입니다.");
            }
        }

        // 동점인 경우 먼저 도달한 사용자에게 높은 순위를 준다.
        sorted.sort(
                Comparator.comparingLong(MemberEntry::contributionScore).reversed()
                        .thenComparing(MemberEntry::scoreReachedAt)
                        .thenComparingLong(MemberEntry::userId)
        );

        List<MemberRank> result = new ArrayList<>();

        // 그룹 시즌별로 1번부터 순위를 부여.
        for (int i = 0; i < sorted.size(); i++) {
            MemberEntry entry = sorted.get(i);

            result.add(new MemberRank(
                    i + 1,
                    entry.userId(),
                    entry.contributionScore(),
                    entry.scoreReachedAt()
            ));
        }

        return List.copyOf(result);
    }
}
