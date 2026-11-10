package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.season.domain.CreatureSpecies;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.CreatureSpeciesRepository;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatureSelectionServiceTest {

    @Mock GroupMembershipRepository membershipRepository;
    @Mock SeasonQueryService seasonQueryService;
    @Mock GroupSeasonRepository groupSeasonRepository;
    @Mock GroupCreatureRepository groupCreatureRepository;
    @Mock CreatureSpeciesRepository speciesRepository;
    @Mock CreatureUpdateEventPublisher eventPublisher;
    @Mock RandomGenerator randomGenerator;

    private MutableClock clock;
    private CreatureSelectionService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-03T00:00:00Z"));
        service = new CreatureSelectionService(
                membershipRepository,
                seasonQueryService,
                groupSeasonRepository,
                groupCreatureRepository,
                speciesRepository,
                eventPublisher,
                randomGenerator,
                clock
        );
    }

    @Test
    void 잠금_대기_중_기한이_지나면_종_선택을_거절한다() {
        Group group = group(10L);
        Season season = season(1L, "ACTIVE", Instant.parse("2026-12-01T00:00:00Z"));
        GroupSeason groupSeason = groupSeason(100L, group, season);
        GroupMembership membership = membership("PRIMARY", group);
        when(membershipRepository.findActiveWithGroupByUserId(1L)).thenReturn(Optional.of(membership));
        when(seasonQueryService.findCurrentSeasonForShare()).thenReturn(season);
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(10L, 1L)).thenAnswer(invocation -> {
            clock.set(groupSeason.getSelectionDeadlineAt());
            return Optional.of(groupSeason);
        });

        assertThatThrownBy(() -> service.selectSpecies(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CREATURE_SELECTION_CLOSED"));
        verify(speciesRepository, never()).findByIdAndStatus(1L, "ACTIVE");
    }

    @Test
    void 모든_활성_그룹_구성원은_활성_종을_조회할_수_있다() {
        Group group = group(10L);
        GroupMembership primary = membership("PRIMARY", group);
        GroupMembership deputy = membership("DEPUTY", group);
        GroupMembership member = membership("MEMBER", group);
        when(speciesRepository.findAllByStatusOrderByDisplayOrderAscIdAsc("ACTIVE")).thenReturn(List.of());
        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.of(primary));
        when(membershipRepository.findActiveWithGroupByUserId(2L))
                .thenReturn(Optional.of(deputy));
        when(membershipRepository.findActiveWithGroupByUserId(3L))
                .thenReturn(Optional.of(member));
        when(membershipRepository.findActiveWithGroupByUserId(4L))
                .thenReturn(Optional.empty());

        assertThatCode(() -> service.listActiveSpecies(1L)).doesNotThrowAnyException();
        assertThatCode(() -> service.listActiveSpecies(2L)).doesNotThrowAnyException();
        assertThatCode(() -> service.listActiveSpecies(3L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.listActiveSpecies(4L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ROLE_FORBIDDEN"));
    }

    @Test
    void 관리자의_종_선택은_공개_계약의_그룹_시즌_사용자_ID를_반환한다() {
        Group group = group(10L);
        Season season = season(1L, "ACTIVE", Instant.parse("2026-12-01T00:00:00Z"));
        GroupSeason groupSeason = groupSeason(100L, group, season);
        GroupMembership membership = membership("PRIMARY", group);
        User selectedBy = membership.getUser();
        when(selectedBy.getId()).thenReturn(7L);
        CreatureSpecies species = org.mockito.Mockito.mock(CreatureSpecies.class);
        when(species.getId()).thenReturn(5L);
        GroupCreature creature = GroupCreature.egg(groupSeason, clock.instant());
        ReflectionTestUtils.setField(creature, "id", 200L);
        when(membershipRepository.findActiveWithGroupByUserId(7L)).thenReturn(Optional.of(membership));
        when(seasonQueryService.findCurrentSeasonForShare()).thenReturn(season);
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(10L, 1L))
                .thenReturn(Optional.of(groupSeason));
        when(speciesRepository.findByIdAndStatus(5L, "ACTIVE")).thenReturn(Optional.of(species));
        when(groupCreatureRepository.findSnapshotByGroupSeasonId(100L)).thenReturn(Optional.of(creature));

        var response = service.selectSpecies(7L, 5L);

        assertThat(response.groupId()).isEqualTo(10L);
        assertThat(response.seasonId()).isEqualTo(1L);
        assertThat(response.creatureSpeciesId()).isEqualTo(5L);
        assertThat(response.selectedByUserId()).isEqualTo(7L);
        assertThat(response.selectedAt()).isEqualTo(clock.instant());
        assertThat(response.selectionDeadlineAt()).isEqualTo(groupSeason.getSelectionDeadlineAt());
    }

    @Test
    void 시즌_종료가_먼저_선점되면_알을_부화시키지_않는다() {
        when(seasonQueryService.findCurrentSeasonForShareIfPresent()).thenReturn(Optional.empty());

        assertThat(service.hatchIfDue(100L)).isEmpty();
        verify(groupSeasonRepository, never()).findForUpdateById(100L);
        verify(groupCreatureRepository, never()).findForUpdateByGroupSeasonId(100L);
    }

    @Test
    void 부화는_시즌_그룹시즌_크리처_순서로_잠근다() {
        Instant startedAt = Instant.parse("2026-11-03T00:00:00Z");
        Group group = group(10L);
        Season season = season(1L, "ACTIVE", Instant.parse("2026-12-01T00:00:00Z"));
        GroupSeason groupSeason = groupSeason(100L, group, season);
        CreatureSpecies species = org.mockito.Mockito.mock(CreatureSpecies.class);
        when(species.getId()).thenReturn(5L);
        groupSeason.selectSpecies(species, org.mockito.Mockito.mock(User.class), startedAt.plusSeconds(60));
        GroupCreature creature = GroupCreature.egg(groupSeason, startedAt);
        ReflectionTestUtils.setField(creature, "id", 200L);
        when(seasonQueryService.findCurrentSeasonForShareIfPresent()).thenReturn(Optional.of(season));
        when(groupSeasonRepository.findForUpdateById(100L)).thenReturn(Optional.of(groupSeason));
        when(groupCreatureRepository.findForUpdateByGroupSeasonId(100L)).thenAnswer(invocation -> {
            clock.set(groupSeason.getSelectionDeadlineAt());
            return Optional.of(creature);
        });

        var result = service.hatchIfDue(100L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().groupSeasonId()).isEqualTo(100L);
        assertThat(result.orElseThrow().groupCreatureId()).isEqualTo(200L);
        assertThat(result.orElseThrow().creatureSpeciesId()).isEqualTo(5L);

        InOrder locks = inOrder(seasonQueryService, groupSeasonRepository, groupCreatureRepository);
        locks.verify(seasonQueryService).findCurrentSeasonForShareIfPresent();
        locks.verify(groupSeasonRepository).findForUpdateById(100L);
        locks.verify(groupCreatureRepository).findForUpdateByGroupSeasonId(100L);
        assertThat(groupSeason.getStatus()).isEqualTo("ACTIVE");
        assertThat(creature.getGrowthStage()).isEqualTo("HATCHLING");
    }

    private Group group(Long id) {
        Group group = org.mockito.Mockito.mock(Group.class);
        lenient().when(group.getId()).thenReturn(id);
        return group;
    }

    private Season season(Long id, String status, Instant endAt) {
        Season season = org.mockito.Mockito.mock(Season.class);
        when(season.getId()).thenReturn(id);
        when(season.getStatus()).thenReturn(status);
        when(season.getEndAt()).thenReturn(endAt);
        return season;
    }

    private GroupSeason groupSeason(Long id, Group group, Season season) {
        GroupSeason groupSeason = GroupSeason.start(group, season, Instant.parse("2026-11-03T00:00:00Z"));
        ReflectionTestUtils.setField(groupSeason, "id", id);
        return groupSeason;
    }

    private GroupMembership membership(String role, Group group) {
        GroupMembership membership = org.mockito.Mockito.mock(GroupMembership.class);
        User user = org.mockito.Mockito.mock(User.class);
        lenient().when(membership.getRole()).thenReturn(role);
        lenient().when(membership.getGroup()).thenReturn(group);
        lenient().when(membership.getUser()).thenReturn(user);
        return membership;
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
