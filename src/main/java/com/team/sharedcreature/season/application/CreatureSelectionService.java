package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
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

    /** 활성 그룹 구성원에게 선택 가능한 종을 안정적인 표시 순서로 반환합니다. */
    @Transactional(readOnly = true)
    public List<CreatureSpeciesResponse> listActiveSpecies(Long userId) {
        requireActiveMembership(userId);
        return speciesRepository.findAllByStatusOrderByDisplayOrderAscIdAsc("ACTIVE").stream()
                .map(CreatureSpeciesResponse::from)
                .toList();
    }

    /** 부화 기한 전에 관리자의 최종 종 선택을 반영하고 같은 종 재선택은 기존 결과를 반환합니다. */
    @Transactional
    public CreatureSelectionResponse selectSpecies(Long userId, Long speciesId) {
        GroupMembership membership = requireAdminMembership(userId);
        Season season = seasonQueryService.findCurrentSeasonForShare();
        GroupSeason groupSeason = groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(
                        membership.getGroup().getId(), season.getId())
                .orElseThrow(() -> notFound("현재 그룹의 시즌 참여 정보를 찾을 수 없습니다."));
        Instant now = Instant.now(clock);
        if (!"ACTIVE".equals(season.getStatus())
                || !now.isBefore(season.getEndAt())
                || !groupSeason.isSelectionOpenAt(now)) {
            throw conflict(ErrorCode.CREATURE_SELECTION_CLOSED, "크리처 선택 시간이 종료됐습니다.");
        }
        CreatureSpecies species = speciesRepository.findByIdAndStatus(speciesId, "ACTIVE")
                .orElseThrow(() -> notFound("선택할 수 있는 크리처 종을 찾을 수 없습니다."));

        boolean changed = groupSeason.selectSpecies(species, membership.getUser(), now);
        if (changed) {
            GroupCreature creature = groupCreatureRepository.findSnapshotByGroupSeasonId(groupSeason.getId())
                    .orElseThrow(() -> notFound("현재 그룹 크리처를 찾을 수 없습니다."));
            publish(groupSeason, creature, now);
        }
        return CreatureSelectionResponse.fromAdminSelection(groupSeason);
    }

    /**
     * Scheduler가 기한이 지난 알을 한 건씩 안전하게 부화시키도록 합니다.
     * 시즌 종료가 먼저이거나 아직 기한 전이면 상태를 변경하지 않습니다.
     */
    @Transactional
    public Optional<HatchResult> hatchIfDue(Long groupSeasonId) {
        Optional<Season> currentSeason = seasonQueryService.findCurrentSeasonForShareIfPresent();
        if (currentSeason.isEmpty()) {
            return Optional.empty();
        }
        Season season = currentSeason.get();
        GroupSeason groupSeason = groupSeasonRepository.findForUpdateById(groupSeasonId)
                .orElseThrow(() -> notFound("그룹 시즌 참여 정보를 찾을 수 없습니다."));
        if (!"PREPARING".equals(groupSeason.getStatus())
                || !season.getId().equals(groupSeason.getSeason().getId())) {
            return Optional.empty();
        }

        GroupCreature creature = groupCreatureRepository.findForUpdateByGroupSeasonId(groupSeason.getId())
                .orElseThrow(() -> notFound("현재 그룹 크리처를 찾을 수 없습니다."));
        Instant now = Instant.now(clock);
        if (now.isBefore(groupSeason.getSelectionDeadlineAt())
                || !"ACTIVE".equals(season.getStatus())
                || !now.isBefore(season.getEndAt())
                || !groupSeason.getSelectionDeadlineAt().isBefore(season.getEndAt())) {
            return Optional.empty();
        }

        if (groupSeason.getSelectedSpecies() == null) {
            List<CreatureSpecies> activeSpecies = speciesRepository
                    .findAllByStatusOrderByDisplayOrderAscIdAsc("ACTIVE");
            if (activeSpecies.isEmpty()) {
                throw new IllegalStateException("No active creature species is available for hatching.");
            }
            groupSeason.selectRandomSpecies(activeSpecies.get(randomGenerator.nextInt(activeSpecies.size())), now);
        }

        creature.hatch(groupSeason.getSelectedSpecies(), groupSeason.getSelectionSource(), now);
        groupSeason.activate(now);
        publish(groupSeason, creature, now);
        return Optional.of(new HatchResult(
                groupSeason.getId(),
                creature.getId(),
                groupSeason.getSelectedSpecies().getId(),
                now
        ));
    }

    private GroupMembership requireActiveMembership(Long userId) {
        return membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));
    }

    private GroupMembership requireAdminMembership(Long userId) {
        GroupMembership membership = requireActiveMembership(userId);
        if (!"PRIMARY".equals(membership.getRole()) && !"DEPUTY".equals(membership.getRole())) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
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

    private BusinessException conflict(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    /** Scheduler 내부에서 사용하는 무작위·관리자 부화 처리 결과입니다. */
    public record HatchResult(
            Long groupSeasonId,
            Long groupCreatureId,
            Long creatureSpeciesId,
            Instant hatchedAt
    ) {
    }
}
