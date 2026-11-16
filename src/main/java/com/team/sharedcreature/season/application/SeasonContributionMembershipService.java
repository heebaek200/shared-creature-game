package com.team.sharedcreature.season.application;

import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 가입·탈퇴·강퇴로 변경된 멤버십 상태를 현재 시즌의 개인 기여도에 반영합니다.
 * 새 기여도를 만들지 않으며 기존 점수와 점수 도달 시각을 보존합니다.
 * 호출자는 시즌 공유 잠금과 사용자 행 잠금을 먼저 확보해야 합니다.
 */
@Service
public class SeasonContributionMembershipService {

    private final GroupSeasonRepository groupSeasonRepository;
    private final SeasonMemberContributionRepository contributionRepository;

    /**
     * 기존 그룹 시즌과 개인 기여도를 조회할 Repository를 주입받습니다.
     * 시즌을 직접 조회하거나 임의로 잠그지 않고 호출자의 잠금 순서를 유지합니다.
     * 별도 트랜잭션 없이 가입·탈퇴·강퇴 트랜잭션에 참여합니다.
     */
    public SeasonContributionMembershipService(
            GroupSeasonRepository groupSeasonRepository,
            SeasonMemberContributionRepository contributionRepository
    ) {
        this.groupSeasonRepository = groupSeasonRepository;
        this.contributionRepository = contributionRepository;
    }

    /**
     * 현재 ACTIVE 시즌에 이미 존재하는 기여도의 참가 상태만 동기화합니다.
     * 시즌 종료 후 과거 기여도를 수정하거나, 아직 퀘스트를 완료하지 않은 사용자에게
     * 0점 기여도 행을 미리 생성하지 않습니다.
     *
     * @param currentSeason 호출자가 공유 잠금 후 확인한 현재 활성 시즌
     * @param membership 가입·탈퇴·강퇴 결과가 반영된 멤버십
     * @param now 멤버십 상태가 변경된 업무 시각
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronizeIfPresent(
            Optional<Season> currentSeason,
            GroupMembership membership,
            Instant now
    ) {
        Objects.requireNonNull(currentSeason, "currentSeason");
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(now, "now");

        // 상태 전이 중 시즌 경계를 넘었다면 지난 시즌의 순위를 수정하지 않습니다.
        Season season = currentSeason.orElse(null);
        if (season == null || !season.isActiveAt(now)) {
            return;
        }

        // 그룹 시즌이 없는 사용자에게 기여도 행을 소급 생성하지 않습니다.
        Long groupId = Objects.requireNonNull(membership.getGroup(), "membership.group").getId();
        Long userId = Objects.requireNonNull(membership.getUser(), "membership.user").getId();
        GroupSeason groupSeason = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(groupId, season.getId())
                .orElse(null);
        if (groupSeason == null) {
            return;
        }

        // 기존 행이 있을 때만 상태와 최신 멤버십을 변경하며 점수는 보존합니다.
        contributionRepository.findByGroupSeasonIdAndUserId(groupSeason.getId(), userId)
                .ifPresent(contribution -> contribution.synchronizeMembership(membership, now));
    }
}
