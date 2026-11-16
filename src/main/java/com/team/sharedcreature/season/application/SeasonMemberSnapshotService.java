package com.team.sharedcreature.season.application;

import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.domain.SeasonMemberRankSnapshot;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberRankSnapshotRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 종료 중인 시즌의 개인 랭킹 Snapshot을 100건씩 저장합니다.
 * 그룹 시즌 단위로 순위를 계산하고 같은 사용자의 그룹 이동 이력도 분리합니다.
 * 시즌 행 배타 잠금 아래 이미 저장된 Snapshot을 보존하며 누락된 행만 생성합니다.
 */
@Service
public class SeasonMemberSnapshotService {

    private static final int BATCH_SIZE = 100;

    private final SeasonRepository seasonRepository;
    private final SeasonMemberContributionRepository contributionRepository;
    private final SeasonMemberRankSnapshotRepository snapshotRepository;
    private final UserProfileRepository profileRepository;
    private final Clock clock;

    /**
     * 종료 시즌의 개인 기여도·Snapshot·프로필 저장소를 주입받습니다.
     * 종료 상태 변경과 개인 Snapshot 저장은 독립 트랜잭션 단위로 실행합니다.
     * Clock을 주입받아 생성 시각을 테스트에서 고정할 수 있습니다.
     */
    public SeasonMemberSnapshotService(
            SeasonRepository seasonRepository,
            SeasonMemberContributionRepository contributionRepository,
            SeasonMemberRankSnapshotRepository snapshotRepository,
            UserProfileRepository profileRepository,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.contributionRepository = contributionRepository;
        this.snapshotRepository = snapshotRepository;
        this.profileRepository = profileRepository;
        this.clock = clock;
    }

    /**
     * 종료 시즌에 누락된 개인 Snapshot을 최대 100건 생성합니다.
     * 그룹 시즌별로 순위를 새로 계산하지만 기존 Snapshot은 변경하지 않습니다.
     * 한 번도 ACTIVE가 아니었던 SCHEDULED 종료 시즌은 랭킹을 생성하지 않습니다.
     *
     * @param seasonId Snapshot 대상 시즌 ID
     * @return 전체 대상·이번 저장·남은 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Progress writeNextBatch(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 그룹 Snapshot과 동일하게 시즌 행을 가장 먼저 배타 잠금합니다.
        Season season = seasonRepository.findForUpdateById(seasonId)
                .orElseThrow(() -> new IllegalStateException("시즌을 찾을 수 없습니다. seasonId=" + seasonId));
        if (!"CLOSING".equals(season.getStatus())) {
            throw new IllegalStateException("CLOSING 시즌만 Snapshot을 생성할 수 있습니다.");
        }
        if (!season.requiresRankingSnapshotOnClosing()) {
            return new Progress(0, 0, 0);
        }

        // 그룹 시즌별로 기여도를 분리합니다. 과거 탈퇴·이동자의 기여도도 포함합니다.
        Map<Long, List<SeasonMemberContribution>> contributionsByGroupSeason = new TreeMap<>();
        for (SeasonMemberContribution contribution : contributionRepository.findAllForSeasonSnapshot(seasonId)) {
            GroupSeason groupSeason = Objects.requireNonNull(contribution.getGroupSeason(), "groupSeason");
            if (groupSeason.getId() == null || groupSeason.getSeason() == null
                    || !seasonId.equals(groupSeason.getSeason().getId())
                    || groupSeason.getGroup() == null || groupSeason.getGroup().getId() == null) {
                throw new IllegalStateException("개인 기여도의 시즌·그룹 관계가 잘못됐습니다.");
            }
            contributionsByGroupSeason.computeIfAbsent(groupSeason.getId(), ignored -> new ArrayList<>())
                    .add(contribution);
        }

        // 그룹 시즌마다 순위를 1부터 다시 부여합니다. ID ASC 순서로 배치를 반복합니다.
        Map<MemberKey, Candidate> expected = new LinkedHashMap<>();
        for (Map.Entry<Long, List<SeasonMemberContribution>> groupEntry : contributionsByGroupSeason.entrySet()) {
            List<SeasonRankingCalculator.MemberEntry> entries = new ArrayList<>();
            Map<Long, SeasonMemberContribution> sourceByUser = new HashMap<>();
            for (SeasonMemberContribution contribution : groupEntry.getValue()) {
                Long userId = Objects.requireNonNull(contribution.getUser(), "user").getId();
                Long score = Objects.requireNonNull(contribution.getContributionScore(), "contributionScore");
                if (userId == null || sourceByUser.putIfAbsent(userId, contribution) != null) {
                    throw new IllegalStateException("같은 그룹 시즌에 개인 기여도가 중복됐습니다.");
                }
                entries.add(new SeasonRankingCalculator.MemberEntry(
                        userId,
                        score,
                        contribution.getScoreReachedAt()
                ));
            }

            // 그룹 시즌별 계산 결과에 원본 기여도를 연결해 Snapshot 후보를 만듭니다.
            for (SeasonRankingCalculator.MemberRank rank : SeasonRankingCalculator.rankMembers(entries)) {
                SeasonMemberContribution contribution = sourceByUser.get(rank.userId());
                MemberKey key = new MemberKey(groupEntry.getKey(), rank.userId());
                expected.put(key, new Candidate(contribution.getGroupSeason(), contribution, rank));
            }
        }

        // 이전 Commit에서 저장한 개인 Snapshot이 현재 계산 순위와 일치하는지 검사합니다.
        List<SeasonMemberRankSnapshot> existing = snapshotRepository.findAllBySeasonId(seasonId);
        Map<MemberKey, SeasonMemberRankSnapshot> existingByKey = new HashMap<>();
        Set<RankKey> existingRanks = new HashSet<>();
        for (SeasonMemberRankSnapshot snapshot : existing) {
            Long groupSeasonId = snapshot.getGroupSeason().getId();
            Long userId = snapshot.getUser().getId();
            MemberKey key = new MemberKey(groupSeasonId, userId);
            RankKey rankKey = new RankKey(groupSeasonId, snapshot.getRankNumber());
            Candidate candidate = expected.get(key);

            // 닉네임과 참가 상태는 기존 Snapshot의 불변 값을 보존합니다.
            if (candidate == null
                    || existingByKey.putIfAbsent(key, snapshot) != null
                    || !existingRanks.add(rankKey)
                    || !Objects.equals(snapshot.getSeason().getId(), seasonId)
                    || !Objects.equals(snapshot.getGroup().getId(), candidate.groupSeason().getGroup().getId())
                    || !Objects.equals(snapshot.getRankNumber(), candidate.rank().rankNumber())
                    || !Objects.equals(snapshot.getContributionScore(), candidate.rank().contributionScore())
                    || !Objects.equals(snapshot.getScoreReachedAt(), candidate.rank().scoreReachedAt())) {
                throw new IllegalStateException("저장된 개인 Snapshot의 식별자·점수·순위가 일치하지 않습니다.");
            }
        }

        // 새로 작성할 최대 100명의 후보를 먼저 결정합니다.
        List<Candidate> pending = new ArrayList<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (Map.Entry<MemberKey, Candidate> entry : expected.entrySet()) {
            if (existingByKey.containsKey(entry.getKey())) {
                continue;
            }
            if (pending.size() >= BATCH_SIZE) {
                break;
            }
            pending.add(entry.getValue());
            userIds.add(entry.getKey().userId());
        }

        // 기존 Snapshot은 프로필을 다시 읽지 않습니다. 새로운 후보에게만 현재 닉네임을 복사합니다.
        Map<Long, UserProfile> profiles = new HashMap<>();
        if (!pending.isEmpty()) {
            for (UserProfile profile : profileRepository.findAllByUserIdIn(userIds)) {
                Long userId = Objects.requireNonNull(profile.getUser(), "profile.user").getId();
                if (userId == null || !userIds.contains(userId)
                        || profiles.putIfAbsent(userId, profile) != null) {
                    throw new IllegalStateException("사용자 프로필 조회 결과가 잘못됐습니다.");
                }
            }
            if (profiles.size() != userIds.size()) {
                throw new IllegalStateException("개인 Snapshot을 위한 사용자 프로필이 누락됐습니다.");
            }
        }

        // 배치 내부의 저장 실패 시 전체가 Rollback되며, 이전 Commit된 Snapshot은 변경하지 않습니다.
        Instant now = Instant.now(clock);
        for (Candidate candidate : pending) {
            Long userId = candidate.contribution().getUser().getId();
            snapshotRepository.save(SeasonMemberRankSnapshot.capture(
                    season,
                    candidate.groupSeason(),
                    candidate.contribution(),
                    profiles.get(userId),
                    candidate.rank(),
                    now
            ));
        }

        return new Progress(expected.size(), pending.size(), expected.size() - existing.size() - pending.size());
    }

    /**
     * 그룹 시즌 ID와 사용자 ID로 Snapshot의 고유키를 표현합니다.
     * 한 사용자가 시즌 중 그룹을 옮긴 경우에도 그룹 시즌별로 별도 보존됩니다.
     * DB의 UNIQUE(group_season_id, user_id) 제약과 동일한 키입니다.
     */
    private record MemberKey(Long groupSeasonId, Long userId) {
    }

    /**
     * 그룹 시즌별 rank_number의 UNIQUE 제약을 검사합니다.
     * 다른 그룹 시즌에서 같은 순위 번호를 사용해도 충돌하지 않습니다.
     * 저장 결과의 중복된 순위를 조기에 거절합니다.
     */
    private record RankKey(Long groupSeasonId, Integer rankNumber) {
    }

    /**
     * 저장 전 계산한 순위와 원본 기여도를 연결합니다.
     * Snapshot을 생성할 때 잘못된 그룹 시즌·사용자 관계를 방지합니다.
     * 이 구조 자체는 DB에 저장하지 않습니다.
     */
    private record Candidate(
            GroupSeason groupSeason,
            SeasonMemberContribution contribution,
            SeasonRankingCalculator.MemberRank rank
    ) {
    }

    /**
     * 개인 Snapshot 한 번의 배치 처리 결과입니다.
     * remaining이 0이면 개인 랭킹의 누락 Snapshot이 없는 상태입니다.
     * 그룹 Snapshot과 시즌 ENDED 확정은 별도 Service가 처리합니다.
     */
    public record Progress(int total, int inserted, int remaining) {
    }
}
