package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature._core.realtime.GroupLivePayloads;
import com.team.sharedcreature._core.realtime.GroupLiveStreamService;
import com.team.sharedcreature._core.realtime.GroupPresenceService;
import com.team.sharedcreature._core.realtime.PresenceMemberPayloadProvider;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.infrastructure.AvatarEquipmentRepository;
import com.team.sharedcreature.season.api.CreatureHomeResponse;
import com.team.sharedcreature.season.application.CreatureHomeSupplementProvider;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.CreatureExperienceLedger;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Presence·기여·아바타를 기존 크리처 홈 REST Snapshot에 결합합니다. */
@Component
public class RealtimeCreatureHomeSupplementProvider
        implements CreatureHomeSupplementProvider, PresenceMemberPayloadProvider {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final CreatureExperienceLedgerRepository ledgerRepository;
    private final SeasonMemberContributionRepository contributionRepository;
    private final UserProfileRepository profileRepository;
    private final AvatarEquipmentRepository equipmentRepository;
    private final GroupSeasonRepository groupSeasonRepository;
    private final SeasonQueryService seasonQueryService;
    private final GroupPresenceService presenceService;
    private final GroupLiveStreamService streamService;

    /** Snapshot 조립에 필요한 기존 저장소와 실시간 메모리 상태를 주입받습니다. */
    public RealtimeCreatureHomeSupplementProvider(
            CreatureExperienceLedgerRepository ledgerRepository,
            SeasonMemberContributionRepository contributionRepository,
            UserProfileRepository profileRepository,
            AvatarEquipmentRepository equipmentRepository,
            GroupSeasonRepository groupSeasonRepository,
            SeasonQueryService seasonQueryService,
            GroupPresenceService presenceService,
            GroupLiveStreamService streamService
    ) {
        this.ledgerRepository = ledgerRepository;
        this.contributionRepository = contributionRepository;
        this.profileRepository = profileRepository;
        this.equipmentRepository = equipmentRepository;
        this.groupSeasonRepository = groupSeasonRepository;
        this.seasonQueryService = seasonQueryService;
        this.presenceService = presenceService;
        this.streamService = streamService;
    }

    /** 현재 크리처의 오늘 경험치·온라인 구성원·최근 활동과 Stream Cursor를 반환합니다. */
    @Override
    @Transactional(readOnly = true)
    public Supplement getSupplement(GroupCreature creature, Long requesterId, Instant now) {
        GroupSeason groupSeason = creature.getGroupSeason();
        Long groupId = groupSeason.getGroup().getId();
        LocalDate businessDate = now.atZone(BUSINESS_ZONE).toLocalDate();
        Instant startsAt = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant endsAt = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        long todayExperience = ledgerRepository.sumExperienceByCreatureAndOccurredAt(
                creature.getId(), startsAt, endsAt);

        List<Long> onlineUserIds = presenceService.activeUserIds(groupId);
        SnapshotData snapshotData = loadSnapshotData(groupSeason.getId(), onlineUserIds);
        List<CreatureHomeResponse.OnlineMemberResponse> onlineMembers = onlineUserIds.stream()
                .map(userId -> onlineMember(userId, snapshotData))
                .toList();

        List<CreatureExperienceLedger> recentLedgers = ledgerRepository.findRecentByGroupCreatureId(
                creature.getId(), PageRequest.of(0, 10));
        Map<Long, UserProfile> recentProfiles = profiles(recentLedgers.stream()
                .map(ledger -> ledger.getQuestCompletion().getUser().getId())
                .toList());
        List<CreatureHomeResponse.ContributionActivityResponse> recentContributions = recentLedgers.stream()
                .map(ledger -> new CreatureHomeResponse.ContributionActivityResponse(
                        ledger.getQuestCompletion().getUser().getId(),
                        requireProfile(recentProfiles, ledger.getQuestCompletion().getUser().getId()).getNickname(),
                        ledger.getExperienceAmount(),
                        ledger.getOccurredAt()
                ))
                .toList();

        return new Supplement(
                todayExperience,
                recentContributions,
                onlineMembers,
                streamService.streamInstanceId(),
                streamService.currentSequence(groupId)
        );
    }

    /** 첫 Presence Session의 현재 프로필·순위·장착 정보를 입장 Payload로 조립합니다. */
    @Override
    @Transactional(readOnly = true)
    public GroupLivePayloads.PresenceJoined getPresenceJoined(Long groupId, Long userId) {
        Season season = seasonQueryService.findCurrentSeason();
        GroupSeason groupSeason = groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(
                        groupId, season.getId())
                .orElseThrow(() -> new IllegalStateException("현재 그룹 시즌 Snapshot이 없습니다."));
        SnapshotData data = loadSnapshotData(groupSeason.getId(), List.of(userId));
        CreatureHomeResponse.OnlineMemberResponse member = onlineMember(userId, data);
        return new GroupLivePayloads.PresenceJoined(
                member.userId(),
                member.nickname(),
                member.rank(),
                member.contributionScore(),
                member.avatar().equipments()
        );
    }

    private SnapshotData loadSnapshotData(Long groupSeasonId, List<Long> onlineUserIds) {
        List<SeasonMemberContribution> contributions =
                contributionRepository.findAllByGroupSeasonIdWithUser(groupSeasonId);
        Map<Long, SeasonMemberContribution> contributionByUser = contributions.stream()
                .collect(Collectors.toMap(value -> value.getUser().getId(), Function.identity()));
        Map<Long, Integer> rankByUser = SeasonRankingCalculator.rankMembers(contributions.stream()
                        .map(value -> new SeasonRankingCalculator.MemberEntry(
                                value.getUser().getId(),
                                value.getContributionScore(),
                                value.getScoreReachedAt()
                        ))
                        .toList()).stream()
                .collect(Collectors.toMap(SeasonRankingCalculator.MemberRank::userId,
                        SeasonRankingCalculator.MemberRank::rankNumber));
        Map<Long, UserProfile> profileByUser = profiles(onlineUserIds);
        Map<Long, CreatureHomeResponse.EquipmentSlotsResponse> equipmentsByUser =
                equipments(onlineUserIds);
        return new SnapshotData(contributionByUser, rankByUser, profileByUser, equipmentsByUser);
    }

    private CreatureHomeResponse.OnlineMemberResponse onlineMember(Long userId, SnapshotData data) {
        UserProfile profile = requireProfile(data.profileByUser(), userId);
        SeasonMemberContribution contribution = data.contributionByUser().get(userId);
        long score = contribution == null ? 0L : contribution.getContributionScore();
        return new CreatureHomeResponse.OnlineMemberResponse(
                userId,
                profile.getNickname(),
                data.rankByUser().get(userId),
                score,
                new CreatureHomeResponse.AvatarResponse(
                        data.equipmentsByUser().getOrDefault(userId, emptyEquipments())
                )
        );
    }

    private Map<Long, UserProfile> profiles(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findAllByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(profile -> profile.getUser().getId(), Function.identity()));
    }

    private UserProfile requireProfile(Map<Long, UserProfile> profiles, Long userId) {
        UserProfile profile = profiles.get(userId);
        if (profile == null) {
            throw new IllegalStateException("사용자 프로필 Snapshot이 없습니다. userId=" + userId);
        }
        return profile;
    }

    private Map<Long, CreatureHomeResponse.EquipmentSlotsResponse> equipments(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Slot, CreatureHomeResponse.EquippedItemResponse>> grouped = new HashMap<>();
        for (AvatarEquipment equipment : equipmentRepository.findAllByUserIdIn(userIds)) {
            if (equipment.getOwnedItem() == null) {
                continue;
            }
            grouped.computeIfAbsent(equipment.getUser().getId(), ignored -> new EnumMap<>(Slot.class))
                    .put(Slot.valueOf(equipment.getSlotCode()), equippedItem(equipment.getOwnedItem()));
        }
        return grouped.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new CreatureHomeResponse.EquipmentSlotsResponse(
                        entry.getValue().get(Slot.HEAD),
                        entry.getValue().get(Slot.FACE),
                        entry.getValue().get(Slot.BODY),
                        entry.getValue().get(Slot.ACCESSORY)
                )
        ));
    }

    private CreatureHomeResponse.EquippedItemResponse equippedItem(OwnedItem ownedItem) {
        ShopItem shopItem = ownedItem.getShopItem();
        return new CreatureHomeResponse.EquippedItemResponse(
                ownedItem.getId(),
                shopItem.getId(),
                shopItem.getName(),
                shopItem.getAssetKey()
        );
    }

    private CreatureHomeResponse.EquipmentSlotsResponse emptyEquipments() {
        return new CreatureHomeResponse.EquipmentSlotsResponse(null, null, null, null);
    }

    private enum Slot {
        HEAD,
        FACE,
        BODY,
        ACCESSORY
    }

    private record SnapshotData(
            Map<Long, SeasonMemberContribution> contributionByUser,
            Map<Long, Integer> rankByUser,
            Map<Long, UserProfile> profileByUser,
            Map<Long, CreatureHomeResponse.EquipmentSlotsResponse> equipmentsByUser
    ) {
    }
}
