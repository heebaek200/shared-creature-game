package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.season.api.CreatureHomeResponse;
import com.team.sharedcreature.season.api.SeasonResponse;
import com.team.sharedcreature.season.domain.CreatureSpecies;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 현재 사용자의 그룹에 한정한 크리처 홈 REST Snapshot을 조합합니다. */
@Service
public class CreatureHomeQueryService {

    private final GroupMembershipRepository membershipRepository;
    private final SeasonQueryService seasonQueryService;
    private final GroupSeasonRepository groupSeasonRepository;
    private final GroupCreatureRepository groupCreatureRepository;
    private final CreatureHomeSupplementProvider supplementProvider;
    private final Clock clock;

    /** 크리처 핵심 조회와 연계 기능 Provider를 주입받습니다. */
    public CreatureHomeQueryService(
            GroupMembershipRepository membershipRepository,
            SeasonQueryService seasonQueryService,
            GroupSeasonRepository groupSeasonRepository,
            GroupCreatureRepository groupCreatureRepository,
            CreatureHomeSupplementProvider supplementProvider,
            Clock clock
    ) {
        this.membershipRepository = membershipRepository;
        this.seasonQueryService = seasonQueryService;
        this.groupSeasonRepository = groupSeasonRepository;
        this.groupCreatureRepository = groupCreatureRepository;
        this.supplementProvider = supplementProvider;
        this.clock = clock;
    }

    /** 다른 그룹 데이터가 섞이지 않도록 현재 활성 멤버십에서 모든 Snapshot 조회를 시작합니다. */
    @Transactional(readOnly = true)
    public CreatureHomeResponse getCreatureHome(Long userId) {
        Instant now = Instant.now(clock);
        GroupMembership membership = membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));
        Season season = seasonQueryService.findCurrentSeason();
        GroupSeason groupSeason = groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(
                        membership.getGroup().getId(), season.getId())
                .orElseThrow(() -> notFound("현재 그룹의 시즌 참여 정보를 찾을 수 없습니다."));
        GroupCreature creature = groupCreatureRepository.findSnapshotByGroupSeasonId(groupSeason.getId())
                .orElseThrow(() -> notFound("현재 그룹 크리처를 찾을 수 없습니다."));
        CreatureHomeSupplementProvider.Supplement supplement = supplementProvider
                .getSupplement(creature, userId, now);

        CreatureSpecies visibleSpecies = creature.getSpecies() != null
                ? creature.getSpecies()
                : groupSeason.getSelectedSpecies();
        Instant hatchesAt = "EGG".equals(creature.getGrowthStage()) && !"ENDED".equals(groupSeason.getStatus())
                ? groupSeason.getSelectionDeadlineAt()
                : null;
        var creatureResponse = new CreatureHomeResponse.CreatureResponse(
                visibleSpecies == null ? null : visibleSpecies.getId(),
                visibleSpecies == null ? null : visibleSpecies.getName(),
                creature.getGrowthStage(),
                hatchesAt,
                creature.getTotalExperience(),
                creature.currentStageThreshold(),
                creature.nextStageThreshold()
        );
        return new CreatureHomeResponse(
                membership.getGroup().getId(),
                SeasonResponse.from(season),
                creatureResponse,
                supplement.todayExperience(),
                supplement.recentContributions(),
                supplement.presentAvatars(),
                supplement.streamInstanceId(),
                supplement.sequence()
        );
    }

    private BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
}
