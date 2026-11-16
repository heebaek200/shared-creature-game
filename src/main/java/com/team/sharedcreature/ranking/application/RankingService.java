package com.team.sharedcreature.ranking.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.ranking.api.RankingResponse;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.RequiredArgsConstructor;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberRankSnapshotRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupRankSnapshotRepository;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 현재 시즌과 종료 시즌의 개인·그룹 랭킹 조회를 담당합니다.
 */
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class RankingService {

    private final SeasonQueryService seasonQueryService;
    private final SeasonRepository seasonRepository;
    private final Clock clock;
    private final GroupMembershipRepository groupMembershipRepository;
    private final SeasonMemberContributionRepository memberContributionRepository;
    private final UserProfileRepository userProfileRepository;
    private final SeasonMemberRankSnapshotRepository memberRankSnapshotRepository;
    private final GroupSeasonRepository groupSeasonRepository;
    private final SeasonGroupScoreRepository groupScoreRepository;
    private final SeasonGroupRankSnapshotRepository groupRankSnapshotRepository;

    /**
     * 현재 소속 그룹의 개인 시즌 랭킹과 조회자 자신의 순위를 반환합니다.
     * 현재 시즌은 기여도로 계산하고 종료 시즌은 확정 Snapshot을 사용합니다.
     *
     * @param userId 인증된 조회자의 사용자 ID
     * @param seasonId 조회할 시즌 ID, null이면 현재 시즌
     * @param page 조회 페이지 번호
     * @param size 페이지 크기
     * @return 개인 랭킹 페이지와 조회자 자신의 전체 순위
     */
    public RankingResponse.PageDto<RankingResponse.MemberDto> getMemberRanking(
            Long userId,
            Long seasonId,
            int page,
            int size
    ) {
        // 과거 소속 이력이 아닌 현재 그룹을 기준으로 조회 권한을 확인합니다.
        Long groupId = requireCurrentGroupId(userId);
        Season season = resolveSeason(seasonId);

        // 해당 그룹이 조회 시즌에 참여하지 않았다면 빈 목록을 사용합니다.
        List<RankingResponse.MemberDto> rankings = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(groupId, season.getId())
                .map(groupSeason -> {
                    if ("ENDED".equals(season.getStatus())) {
                        return loadEndedMemberRanking(groupSeason.getId());
                    }

                    return loadCurrentMemberRanking(groupSeason.getId());
                })
                .orElseGet(List::of);

        // 페이지를 자르기 전에 전체 목록에서 조회자 자신의 순위를 찾습니다.
        RankingResponse.CurrentSubject currentSubject = rankings.stream()
                .filter(member -> userId.equals(member.userId()))
                .findFirst()
                .map(member -> new RankingResponse.CurrentSubject(
                        member.userId(),
                        member.rank(),
                        member.contributionScore()
                ))
                .orElse(null);

        return toPage(rankings, page, size, currentSubject);
    }

    /**
     * 전체 그룹의 시즌 랭킹과 조회자의 현재 소속 그룹 순위를 반환합니다.
     * 그룹에 가입하지 않은 사용자도 전체 그룹 랭킹을 조회할 수 있습니다.
     *
     * @param userId 인증된 조회자의 사용자 ID
     * @param seasonId 조회할 시즌 ID, null이면 현재 시즌
     * @param page 조회 페이지 번호
     * @param size 페이지 크기
     * @return 그룹 랭킹 페이지와 현재 소속 그룹의 전체 순위
     */
    public RankingResponse.PageDto<RankingResponse.GroupDto> getGroupRanking(
            Long userId,
            Long seasonId,
            int page,
            int size
    ) {
        Season season = resolveSeason(seasonId);

        // 종료 시즌은 확정 Snapshot, 현재 시즌은 저장된 점수로 계산합니다.
        List<RankingResponse.GroupDto> rankings =
                "ENDED".equals(season.getStatus())
                        ? loadEndedGroupRanking(season.getId())
                        : loadCurrentGroupRanking(season.getId());

        // 현재 활성 그룹이 없어도 전체 랭킹 조회는 허용합니다.
        Long currentGroupId = groupMembershipRepository
                .findActiveWithGroupByUserId(userId)
                .filter(membership ->
                        "ACTIVE".equals(membership.getGroup().getStatus())
                )
                .map(membership -> membership.getGroup().getId())
                .orElse(null);

        // 현재 페이지가 아닌 전체 목록에서 소속 그룹의 순위를 찾습니다.
        RankingResponse.CurrentSubject currentSubject = rankings.stream()
                .filter(group -> group.groupId().equals(currentGroupId))
                .findFirst()
                .map(group -> new RankingResponse.CurrentSubject(
                        group.groupId(),
                        group.rank(),
                        group.score()
                ))
                .orElse(null);

        return toPage(rankings, page, size, currentSubject);
    }

    /**
     * 랭킹을 조회할 현재 시즌 또는 종료 시즌을 선택합니다.
     * 종료 시즌을 직접 지정하면 현재 시즌의 준비 상태와 무관하게 조회합니다.
     *
     * @param seasonId 지정한 시즌 ID, null이면 현재 시즌
     * @return 랭킹 조회에 사용할 시즌
     */
    private Season resolveSeason(Long seasonId) {
        // 미지정 요청은 기존 서비스의 현재 시즌 판정 규칙을 사용합니다.
        if (seasonId == null) {
            return seasonQueryService.findCurrentSeason();
        }

        if (seasonId < 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "seasonId는 1 이상이어야 합니다."
            );
        }

        Season season = seasonRepository.findById(seasonId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "조회할 수 없는 seasonId입니다."
                ));

        // 종료 시즌은 현재 시즌을 추가 조회하지 않고 Snapshot 조회로 연결합니다.
        if ("ENDED".equals(season.getStatus())) {
            return season;
        }

        // ACTIVE 상태라도 실제 시즌 시간 범위에 포함되어야 합니다.
        if (season.isActiveAt(Instant.now(clock))) {
            return season;
        }

        // 예정 시즌이나 종료 처리 중인 시즌의 미확정 기록은 노출하지 않습니다.
        throw new BusinessException(
                ErrorCode.INVALID_FIELD_VALUE,
                "현재 진행 중이거나 종료된 시즌만 조회할 수 있습니다."
        );
    }

    /**
     * 개인 랭킹 조회자가 현재 소속된 활성 그룹의 ID를 반환합니다.
     * 과거 소속 이력만으로는 해당 그룹의 개인 랭킹을 조회할 수 없습니다.
     *
     * @param userId 인증된 조회자의 사용자 ID
     * @return 현재 소속된 활성 그룹 ID
     */
    private Long requireCurrentGroupId(Long userId) {
        // LEFT, REMOVED 이력은 조회자의 현재 소속으로 인정하지 않습니다.
        GroupMembership membership = groupMembershipRepository
                .findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ROLE_FORBIDDEN,
                        "개인 랭킹은 현재 소속된 그룹에서만 조회할 수 있습니다."
                ));

        // 멤버십뿐 아니라 그룹도 현재 활성 상태인지 확인합니다.
        if (!"ACTIVE".equals(membership.getGroup().getStatus())) {
            throw new BusinessException(
                    ErrorCode.ROLE_FORBIDDEN,
                    "활성 그룹의 구성원만 개인 랭킹을 조회할 수 있습니다."
            );
        }

        return membership.getGroup().getId();
    }

    /**
     * 현재 시즌의 그룹 내 개인 랭킹을 계산하고 응답 목록으로 변환합니다.
     * 탈퇴·강퇴 기록을 포함하며 닉네임은 일괄 조회합니다.
     *
     * @param groupSeasonId 조회할 그룹 시즌 ID
     * @return 전체 순위순으로 정렬된 개인 랭킹 목록
     */
    private List<RankingResponse.MemberDto> loadCurrentMemberRanking(
            Long groupSeasonId
    ) {
        List<SeasonMemberContribution> contributions =
                memberContributionRepository.findAllForMemberRanking(groupSeasonId);

        // 기록이 없으면 프로필 조회와 순위 계산을 생략합니다.
        if (contributions.isEmpty()) {
            return List.of();
        }

        // 점수 내림차순 → 도달 시각 오름차순 → 사용자 ID 오름차순.
        List<SeasonRankingCalculator.MemberRank> ranks =
                SeasonRankingCalculator.rankMembers(
                        contributions.stream()
                                .map(contribution ->
                                        new SeasonRankingCalculator.MemberEntry(
                                                contribution.getUser().getId(),
                                                contribution.getContributionScore(),
                                                contribution.getScoreReachedAt()
                                        )
                                )
                                .toList()
                );

        // 계산된 순위에 원본 참여 상태를 연결하기 위한 사용자별 조회표입니다.
        Map<Long, SeasonMemberContribution> contributionsByUserId =
                contributions.stream()
                        .collect(Collectors.toMap(
                                contribution -> contribution.getUser().getId(),
                                Function.identity()
                        ));

        // 사용자마다 조회하지 않고 필요한 프로필을 한 번에 조회합니다.
        Map<Long, UserProfile> profilesByUserId =
                userProfileRepository
                        .findAllByUserIdIn(contributionsByUserId.keySet())
                        .stream()
                        .collect(Collectors.toMap(
                                profile -> profile.getUser().getId(),
                                Function.identity()
                        ));

        return ranks.stream()
                .map(rank -> {
                    SeasonMemberContribution contribution =
                            contributionsByUserId.get(rank.userId());

                    UserProfile profile = profilesByUserId.get(rank.userId());

                    // 프로필 누락으로 사용자를 순위에서 임의로 제외하지 않습니다.
                    if (profile == null) {
                        throw new BusinessException(
                                ErrorCode.INTERNAL_SERVER_ERROR,
                                "랭킹 사용자 프로필이 누락되었습니다."
                        );
                    }

                    return new RankingResponse.MemberDto(
                            rank.rankNumber(),
                            rank.userId(),
                            profile.getNickname(),
                            rank.contributionScore(),
                            rank.scoreReachedAt(),
                            contribution.getParticipantStatus()
                    );
                })
                .toList();
    }

    /**
     * 종료 시즌의 그룹 내 개인 랭킹을 응답 목록으로 변환합니다.
     * 저장된 확정 순위와 종료 시점의 표시 정보를 그대로 사용합니다.
     *
     * @param groupSeasonId 조회할 그룹 시즌 ID
     * @return 확정 순위순으로 정렬된 개인 랭킹 목록
     */
    private List<RankingResponse.MemberDto> loadEndedMemberRanking(
            Long groupSeasonId
    ) {
        // Repository에서 확정 순위 오름차순으로 조회합니다.
        return memberRankSnapshotRepository
                .findAllByGroupSeasonIdOrderByRankNumberAsc(groupSeasonId)
                .stream()
                .map(snapshot -> new RankingResponse.MemberDto(
                        snapshot.getRankNumber(),
                        snapshot.getUser().getId(),
                        snapshot.getNicknameSnapshot(),
                        snapshot.getContributionScore(),
                        snapshot.getScoreReachedAt(),
                        snapshot.getParticipantStatus()
                ))
                .toList();
    }

    /**
     * 현재 시즌의 전체 그룹 순위를 계산하고 응답 목록으로 변환합니다.
     * 저장된 시즌 점수를 사용하며 탈퇴자의 기여도를 차감하지 않습니다.
     *
     * @param seasonId 조회할 시즌 ID
     * @return 전체 순위순으로 정렬된 그룹 랭킹 목록
     */
    private List<RankingResponse.GroupDto> loadCurrentGroupRanking(
            Long seasonId
    ) {
        List<SeasonGroupScore> scores =
                groupScoreRepository.findAllForGroupRanking(seasonId);

        if (scores.isEmpty()) {
            return List.of();
        }

        // 계산기가 0점 그룹의 도달 시각을 보완할 수 있도록 생성 시각도 전달합니다.
        List<SeasonRankingCalculator.GroupRank> ranks =
                SeasonRankingCalculator.rankGroups(
                        scores.stream()
                                .map(score ->
                                        new SeasonRankingCalculator.GroupEntry(
                                                score.getGroupSeason().getGroup().getId(),
                                                score.getScore(),
                                                score.getScoreReachedAt(),
                                                score.getCreatedAt()
                                        )
                                )
                                .toList()
                );

        // 순위 결과에 그룹명을 연결하기 위한 그룹별 조회표입니다.
        Map<Long, SeasonGroupScore> scoresByGroupId =
                scores.stream()
                        .collect(Collectors.toMap(
                                score -> score.getGroupSeason().getGroup().getId(),
                                Function.identity()
                        ));

        // 정렬된 계산 결과를 순회하므로 Map의 저장 순서는 영향을 주지 않습니다.
        return ranks.stream()
                .map(rank -> {
                    SeasonGroupScore score = scoresByGroupId.get(rank.groupId());

                    return new RankingResponse.GroupDto(
                            rank.rankNumber(),
                            rank.groupId(),
                            score.getGroupSeason().getGroup().getName(),
                            rank.score(),
                            rank.scoreReachedAt()
                    );
                })
                .toList();
    }

    /**
     * 종료 시즌의 전체 그룹 랭킹을 응답 목록으로 변환합니다.
     * 종료 시점에 저장한 확정 순위와 그룹명을 그대로 사용합니다.
     *
     * @param seasonId 조회할 시즌 ID
     * @return 확정 순위순으로 정렬된 그룹 랭킹 목록
     */
    private List<RankingResponse.GroupDto> loadEndedGroupRanking(
            Long seasonId
    ) {
        // 저장된 확정 순위 오름차순으로 조회하며 순위를 다시 계산하지 않습니다.
        return groupRankSnapshotRepository
                .findAllBySeasonIdOrderByRankNumberAsc(seasonId)
                .stream()
                .map(snapshot -> new RankingResponse.GroupDto(
                        snapshot.getRankNumber(),
                        snapshot.getGroup().getId(),
                        snapshot.getGroupNameSnapshot(),
                        snapshot.getScore(),
                        snapshot.getScoreReachedAt()
                ))
                .toList();
    }

    /**
     * 전체 순위가 확정된 목록에서 요청한 페이지를 구성합니다.
     * 현재 조회 대상의 순위는 페이지와 별도로 전달받습니다.
     *
     * @param rankings 전체 순위순으로 정렬된 랭킹 목록
     * @param page 요청 페이지 번호
     * @param size 요청 페이지 크기
     * @param currentSubject 전체 목록에서 확인한 현재 조회 대상의 순위
     * @param <T> 개인 또는 그룹 랭킹 항목 타입
     * @return 페이지 목록과 랭킹 전용 메타데이터
     */
    private <T> RankingResponse.PageDto<T> toPage(
            List<T> rankings,
            int page,
            int size,
            RankingResponse.CurrentSubject currentSubject
    ) {
        if (page < 0 || size < 1 || size > 50) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "page는 0 이상, size는 1 이상 50 이하여야 합니다."
            );
        }

        long totalElements = rankings.size();

        // 빈 목록의 전체 페이지 수는 0입니다.
        int totalPages = (int) ((totalElements + size - 1) / size);

        // 큰 페이지 번호에서도 곱셈이 넘치지 않도록 long으로 계산합니다.
        long offset = (long) page * size;

        // 마지막 페이지를 초과하면 빈 목록을 반환합니다.
        int fromIndex = (int) Math.min(offset, totalElements);
        int toIndex = (int) Math.min(offset + size, totalElements);

        List<T> content = List.copyOf(
                rankings.subList(fromIndex, toIndex)
        );

        // currentSubject는 현재 페이지에 포함되는지와 관계없이 유지합니다.
        RankingResponse.Meta meta = new RankingResponse.Meta(
                page,
                size,
                totalElements,
                totalPages,
                currentSubject
        );

        return new RankingResponse.PageDto<>(content, meta);
    }
}
