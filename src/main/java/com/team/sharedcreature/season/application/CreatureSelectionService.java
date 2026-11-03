package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.season.api.CreatureSelectionResponse;
import com.team.sharedcreature.season.api.CreatureSpeciesResponse;
import com.team.sharedcreature.season.domain.CreatureSpecies;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.CreatureSpeciesRepository;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** 관리자 종 선택과 Scheduler가 재사용할 무작위 부화 경계를 담당합니다. */
@Service
public class CreatureSelectionService {

    private final GroupMembershipRepository membershipRepository;
    private final SeasonQueryService seasonQueryService;
    private final GroupSeasonRepository groupSeasonRepository;
    private final GroupCreatureRepository groupCreatureRepository;
    private final CreatureSpeciesRepository speciesRepository;
    private final CreatureUpdateEventPublisher eventPublisher;
    private final RandomGenerator randomGenerator;
    private final Clock clock;

    /** 선택·부화에 필요한 저장소, 공통 시각과 테스트 가능한 난수 공급자를 주입받습니다. */
    public CreatureSelectionService(
            GroupMembershipRepository membershipRepository,
            SeasonQueryService seasonQueryService,
            GroupSeasonRepository groupSeasonRepository,
            GroupCreatureRepository groupCreatureRepository,
            CreatureSpeciesRepository speciesRepository,
            CreatureUpdateEventPublisher eventPublisher,
            RandomGenerator randomGenerator,
            Clock clock
    ) {
        this.membershipRepository = membershipRepository;
        this.seasonQueryService = seasonQueryService;
        this.groupSeasonRepository = groupSeasonRepository;
        this.groupCreatureRepository = groupCreatureRepository;
        this.speciesRepository = speciesRepository;
        this.eventPublisher = eventPublisher;
        this.randomGenerator = randomGenerator;
        this.clock = clock;
    }

    /** 주·부 관리자에게 현재 선택 가능한 활성 종을 안정적인 표시 순서로 반환합니다. */
    @Transactional(readOnly = true)
    public List<CreatureSpeciesResponse> listActiveSpecies(Long userId) {
        requireAdminMembership(userId);
        return speciesRepository.findAllByStatusOrderByDisplayOrderAscIdAsc("ACTIVE").stream()
                .map(CreatureSpeciesResponse::from)
                .toList();
    }

    /** 부화 기한 전에 관리자의 최종 종 선택을 반영하고 같은 종 재선택은 기존 결과를 반환합니다. */
    @Transactional
    public CreatureSelectionResponse selectSpecies(Long userId, Long speciesId) {
        Instant now = Instant.now(clock);
        GroupMembership membership = requireAdminMembership(userId);
        Season season = seasonQueryService.findCurrentSeason();
        GroupSeason groupSeason = groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(
                        membership.getGroup().getId(), season.getId())
                .orElseThrow(() -> conflict("GROUP_SEASON_UNAVAILABLE", "현재 그룹의 시즌 참여 정보가 없습니다."));
        if (!groupSeason.isSelectionOpenAt(now)) {
            throw conflict("CREATURE_SELECTION_CLOSED", "크리처 선택 시간이 종료됐습니다.");
        }
        CreatureSpecies species = speciesRepository.findByIdAndStatus(speciesId, "ACTIVE")
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "CREATURE_SPECIES_NOT_FOUND",
                        "선택할 수 있는 크리처 종을 찾을 수 없습니다."
                ));

        boolean changed = groupSeason.selectSpecies(species, membership.getUser(), now);
        if (changed) {
            GroupCreature creature = groupCreatureRepository.findSnapshotByGroupSeasonId(groupSeason.getId())
                    .orElseThrow(() -> conflict("GROUP_CREATURE_UNAVAILABLE", "현재 그룹 크리처가 없습니다."));
            publish(groupSeason, creature, now);
        }
        return CreatureSelectionResponse.from(groupSeason);
    }

    /**
     * Scheduler가 기한이 지난 알을 한 건씩 안전하게 부화시키도록 합니다.
     * 시즌 종료가 먼저이거나 아직 기한 전이면 상태를 변경하지 않습니다.
     */
    @Transactional
    public Optional<CreatureSelectionResponse> hatchIfDue(Long groupSeasonId, Instant now) {
        GroupSeason groupSeason = groupSeasonRepository.findForUpdateById(groupSeasonId)
                .orElseThrow(() -> conflict("GROUP_SEASON_UNAVAILABLE", "그룹 시즌 참여 정보가 없습니다."));
        if (!"PREPARING".equals(groupSeason.getStatus())
                || now.isBefore(groupSeason.getSelectionDeadlineAt())
                || !"ACTIVE".equals(groupSeason.getSeason().getStatus())
                || !now.isBefore(groupSeason.getSeason().getEndAt())
                || !groupSeason.getSelectionDeadlineAt().isBefore(groupSeason.getSeason().getEndAt())) {
            return Optional.empty();
        }

        if (groupSeason.getSelectedSpecies() == null) {
            List<CreatureSpecies> activeSpecies = speciesRepository
                    .findAllByStatusOrderByDisplayOrderAscIdAsc("ACTIVE");
            if (activeSpecies.isEmpty()) {
                throw conflict("ACTIVE_CREATURE_SPECIES_UNAVAILABLE", "부화할 활성 크리처 종이 없습니다.");
            }
            groupSeason.selectRandomSpecies(activeSpecies.get(randomGenerator.nextInt(activeSpecies.size())), now);
        }

        GroupCreature creature = groupCreatureRepository.findForUpdateByGroupSeasonId(groupSeason.getId())
                .orElseThrow(() -> conflict("GROUP_CREATURE_UNAVAILABLE", "현재 그룹 크리처가 없습니다."));
        creature.hatch(groupSeason.getSelectedSpecies(), groupSeason.getSelectionSource(), now);
        groupSeason.activate(now);
        publish(groupSeason, creature, now);
        return Optional.of(CreatureSelectionResponse.from(groupSeason));
    }

    private GroupMembership requireAdminMembership(Long userId) {
        GroupMembership membership = membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "GROUP_MEMBERSHIP_REQUIRED",
                        "활성 그룹 소속이 필요합니다."
                ));
        if (!"PRIMARY".equals(membership.getRole()) && !"DEPUTY".equals(membership.getRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "요청 권한이 없습니다.");
        }
        return membership;
    }

    private void publish(GroupSeason groupSeason, GroupCreature creature, Instant now) {
        eventPublisher.publishAfterCommit(new CreatureUpdatedEvent(
                groupSeason.getGroup().getId(),
                creature.getId(),
                creature.getGrowthStage(),
                creature.getTotalExperience(),
                now
        ));
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
