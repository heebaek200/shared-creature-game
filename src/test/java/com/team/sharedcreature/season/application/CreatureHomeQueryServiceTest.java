package com.team.sharedcreature.season.application;

import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.season.domain.CreatureSpecies;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatureHomeQueryServiceTest {

    @Test
    void 현재_멤버십의_그룹과_시즌으로만_크리처_Snapshot을_조회한다() {
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        SeasonQueryService seasonQueryService = mock(SeasonQueryService.class);
        GroupSeasonRepository groupSeasonRepository = mock(GroupSeasonRepository.class);
        GroupCreatureRepository groupCreatureRepository = mock(GroupCreatureRepository.class);
        CreatureHomeSupplementProvider supplementProvider = mock(CreatureHomeSupplementProvider.class);
        Instant now = Instant.parse("2026-11-03T00:00:00Z");
        CreatureHomeQueryService service = new CreatureHomeQueryService(
                membershipRepository,
                seasonQueryService,
                groupSeasonRepository,
                groupCreatureRepository,
                supplementProvider,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        when(group.getName()).thenReturn("테스트 그룹");
        GroupMembership membership = mock(GroupMembership.class);
        when(membership.getGroup()).thenReturn(group);
        Season season = mock(Season.class);
        when(season.getId()).thenReturn(20L);
        when(season.getSeasonNumber()).thenReturn(1);
        when(season.getStartAt()).thenReturn(now.minusSeconds(60));
        when(season.getEndAt()).thenReturn(now.plusSeconds(60));
        when(season.getStatus()).thenReturn("ACTIVE");
        GroupSeason groupSeason = GroupSeason.start(group, season, now);
        ReflectionTestUtils.setField(groupSeason, "id", 30L);
        GroupCreature creature = GroupCreature.egg(groupSeason, now);
        ReflectionTestUtils.setField(creature, "id", 40L);
        UUID streamId = UUID.randomUUID();
        when(membershipRepository.findActiveWithGroupByUserId(1L)).thenReturn(Optional.of(membership));
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(10L, 20L))
                .thenReturn(Optional.of(groupSeason));
        when(groupCreatureRepository.findSnapshotByGroupSeasonId(30L)).thenReturn(Optional.of(creature));
        when(supplementProvider.getSupplement(creature, 1L, now)).thenReturn(
                new CreatureHomeSupplementProvider.Supplement(0, List.of(), List.of(), streamId, 0));

        var response = service.getCreatureHome(1L);

        assertThat(response.groupId()).isEqualTo(10L);
        assertThat(response.groupName()).isEqualTo("테스트 그룹");
        assertThat(response.season().seasonNumber()).isEqualTo(1);
        assertThat(response.creature().groupCreatureId()).isEqualTo(40L);
        assertThat(response.creature().creatureSpeciesId()).isNull();
        assertThat(response.creature().growthStage()).isEqualTo("EGG");
        assertThat(response.creature().selectionDeadlineAt()).isEqualTo(now.plusSeconds(72L * 60 * 60));
        assertThat(response.onlineMembers()).isEmpty();
        assertThat(response.recentContributions()).isEmpty();
        assertThat(response.snapshotAt()).isEqualTo(now);
        assertThat(response.streamInstanceId()).isEqualTo(streamId);
        assertThat(response.sequence()).isZero();
        verify(groupSeasonRepository).findSnapshotByGroupIdAndSeasonId(10L, 20L);
        verify(groupCreatureRepository).findSnapshotByGroupSeasonId(30L);
    }

    @Test
    void 알_상태에서_종을_선택했다면_선택한_종_ID를_반환한다() {
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        SeasonQueryService seasonQueryService = mock(SeasonQueryService.class);
        GroupSeasonRepository groupSeasonRepository = mock(GroupSeasonRepository.class);
        GroupCreatureRepository groupCreatureRepository = mock(GroupCreatureRepository.class);
        CreatureHomeSupplementProvider supplementProvider = mock(CreatureHomeSupplementProvider.class);
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        CreatureHomeQueryService service = new CreatureHomeQueryService(
                membershipRepository,
                seasonQueryService,
                groupSeasonRepository,
                groupCreatureRepository,
                supplementProvider,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        GroupMembership membership = mock(GroupMembership.class);
        when(membership.getGroup()).thenReturn(group);
        Season season = mock(Season.class);
        when(season.getId()).thenReturn(20L);
        GroupSeason groupSeason = mock(GroupSeason.class);
        when(groupSeason.getId()).thenReturn(30L);
        CreatureSpecies selectedSpecies = mock(CreatureSpecies.class);
        when(selectedSpecies.getId()).thenReturn(50L);
        when(groupSeason.getSelectedSpecies()).thenReturn(selectedSpecies);
        GroupCreature creature = mock(GroupCreature.class);
        when(creature.getId()).thenReturn(40L);
        when(creature.getGrowthStage()).thenReturn("EGG");
        when(creature.getTotalExperience()).thenReturn(100L);
        when(creature.nextStageThreshold()).thenReturn(45_000L);
        UUID streamId = UUID.randomUUID();
        when(membershipRepository.findActiveWithGroupByUserId(1L)).thenReturn(Optional.of(membership));
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(10L, 20L))
                .thenReturn(Optional.of(groupSeason));
        when(groupCreatureRepository.findSnapshotByGroupSeasonId(30L)).thenReturn(Optional.of(creature));
        when(supplementProvider.getSupplement(creature, 1L, now)).thenReturn(
                new CreatureHomeSupplementProvider.Supplement(100L, List.of(), List.of(), streamId, 1L));

        var response = service.getCreatureHome(1L);

        assertThat(response.creature().growthStage()).isEqualTo("EGG");
        assertThat(response.creature().creatureSpeciesId()).isEqualTo(50L);
    }

    @Test
    void 부화_후에도_저장된_그룹별_종_선택_마감_시각을_반환한다() {
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        SeasonQueryService seasonQueryService = mock(SeasonQueryService.class);
        GroupSeasonRepository groupSeasonRepository = mock(GroupSeasonRepository.class);
        GroupCreatureRepository groupCreatureRepository = mock(GroupCreatureRepository.class);
        CreatureHomeSupplementProvider supplementProvider = mock(CreatureHomeSupplementProvider.class);
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        Instant selectionDeadlineAt = Instant.parse("2026-11-06T12:00:00Z");
        CreatureHomeQueryService service = new CreatureHomeQueryService(
                membershipRepository,
                seasonQueryService,
                groupSeasonRepository,
                groupCreatureRepository,
                supplementProvider,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        when(group.getName()).thenReturn("중도 생성 그룹");
        GroupMembership membership = mock(GroupMembership.class);
        when(membership.getGroup()).thenReturn(group);
        Season season = mock(Season.class);
        when(season.getId()).thenReturn(20L);
        GroupSeason groupSeason = mock(GroupSeason.class);
        when(groupSeason.getId()).thenReturn(30L);
        when(groupSeason.getSelectionDeadlineAt()).thenReturn(selectionDeadlineAt);
        CreatureSpecies species = mock(CreatureSpecies.class);
        when(species.getId()).thenReturn(50L);
        GroupCreature creature = mock(GroupCreature.class);
        when(creature.getId()).thenReturn(40L);
        when(creature.getSpecies()).thenReturn(species);
        when(creature.getGrowthStage()).thenReturn("HATCHLING");
        when(creature.getTotalExperience()).thenReturn(100L);
        when(creature.nextStageThreshold()).thenReturn(45_000L);
        UUID streamId = UUID.randomUUID();
        when(membershipRepository.findActiveWithGroupByUserId(1L)).thenReturn(Optional.of(membership));
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(10L, 20L))
                .thenReturn(Optional.of(groupSeason));
        when(groupCreatureRepository.findSnapshotByGroupSeasonId(30L)).thenReturn(Optional.of(creature));
        when(supplementProvider.getSupplement(creature, 1L, now)).thenReturn(
                new CreatureHomeSupplementProvider.Supplement(100L, List.of(), List.of(), streamId, 2L));

        var response = service.getCreatureHome(1L);

        assertThat(response.creature().creatureSpeciesId()).isEqualTo(50L);
        assertThat(response.creature().growthStage()).isEqualTo("HATCHLING");
        assertThat(response.creature().selectionDeadlineAt()).isEqualTo(selectionDeadlineAt);
    }
}
