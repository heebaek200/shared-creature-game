package com.team.sharedcreature.group.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.CreateGroupRequest;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupAdminChangeRepository;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupMembershipServiceTest {

    @Mock UserRepository userRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMembershipRepository membershipRepository;
    @Mock GroupJoinRestrictionRepository restrictionRepository;
    @Mock GroupInviteLinkRepository inviteLinkRepository;
    @Mock GroupAdminChangeRepository adminChangeRepository;
    @Mock SeasonRepository seasonRepository;
    @Mock GroupSeasonRepository groupSeasonRepository;
    @Mock GroupCreatureRepository groupCreatureRepository;
    @Mock SeasonGroupScoreRepository groupScoreRepository;

    private GroupMembershipService service;
    private User user;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-03T00:00:00Z"));
        service = new GroupMembershipService(
                userRepository,
                groupRepository,
                membershipRepository,
                restrictionRepository,
                inviteLinkRepository,
                adminChangeRepository,
                seasonRepository,
                groupSeasonRepository,
                groupCreatureRepository,
                groupScoreRepository,
                clock,
                "http://localhost:5173"
        );
        user = mock(User.class);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void createGroup은_그룹_초대_시즌_알_점수를_같은_업무흐름에서_생성한다() {
        when(user.getId()).thenReturn(1L);
        when(groupRepository.existsByNormalizedName("새싹모임")).thenReturn(false);
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);
        Season season = mock(Season.class);
        when(season.getStatus()).thenReturn("ACTIVE");
        when(season.getEndAt()).thenReturn(Instant.parse("2026-12-01T00:00:00Z"));
        when(seasonRepository.findForShareByStatus(org.mockito.ArgumentMatchers.eq("ACTIVE"), any(Pageable.class)))
                .thenReturn(List.of(season));
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupSeasonRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("새싹모임", "함께 걸어요")
        );

        assertThat(result.group().group().name()).isEqualTo("새싹모임");
        assertThat(result.initialInvitationUrl()).startsWith("http://localhost:5173/invite/");
        verify(inviteLinkRepository).save(any());
        verify(adminChangeRepository).save(any());
        verify(groupCreatureRepository).save(any());
        verify(groupScoreRepository).save(any());
    }

    @Test
    void createGroup은_정규화한_이름이_중복이면_거절한다() {
        when(groupRepository.existsByNormalizedName("중복그룹")).thenReturn(true);

        assertThatThrownBy(() -> service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("중복그룹", "")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 그룹 이름입니다.");
    }

    @Test
    void createGroup은_시즌_잠금_대기_중_종료_시각이_지나면_그룹을_생성하지_않는다() {
        when(user.getId()).thenReturn(1L);
        when(groupRepository.existsByNormalizedName("경계그룹")).thenReturn(false);
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        Season season = mock(Season.class);
        Instant endsAt = Instant.parse("2026-11-03T00:00:01Z");
        when(season.getStatus()).thenReturn("ACTIVE");
        when(season.getEndAt()).thenReturn(endsAt);
        when(seasonRepository.findForShareByStatus(org.mockito.ArgumentMatchers.eq("ACTIVE"), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    clock.set(endsAt);
                    return List.of(season);
                });

        assertThatThrownBy(() -> service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("경계그룹", "")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        verify(groupRepository, never()).save(any());
        verify(groupSeasonRepository, never()).save(any());
    }

    @Test
    void joinGroup은_사용자와_그룹을_잠근_뒤_일반_구성원을_등록한다() {
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        when(group.getStatus()).thenReturn("ACTIVE");
        when(group.getVisibility()).thenReturn("PUBLIC");
        when(group.getCurrentMemberCount()).thenReturn(19);
        when(group.getCapacity()).thenReturn(20);
        when(groupRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(group));
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);
        when(membershipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.joinGroup(new LoginUser(1L, "가입자", Set.of()), 10L);

        assertThat(result.groupId()).isEqualTo(10L);
        assertThat(result.role()).isEqualTo("MEMBER");
        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(group).addMember(clock.instant());
    }

    @Test
    void joinGroup은_잠금_후_정원이_가득_찬_그룹을_거절한다() {
        Group group = mock(Group.class);
        when(group.getStatus()).thenReturn("ACTIVE");
        when(group.getVisibility()).thenReturn("PUBLIC");
        when(group.getCurrentMemberCount()).thenReturn(20);
        when(group.getCapacity()).thenReturn(20);
        when(groupRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(group));
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.joinGroup(new LoginUser(1L, "가입자", Set.of()), 10L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("GROUP_FULL"));
        verify(membershipRepository, never()).save(any());
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
