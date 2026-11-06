package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.CreateGroupRequest;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.infrastructure.GroupAdminChangeRepository;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 그룹 생성·가입·공개 목록 기능을 단위 테스트합니다.
 * 현재 시즌 확인은 SeasonQueryService를 통해 수행하도록 검증합니다.
 * 시즌 종료 경계에서는 그룹과 시즌 참여가 생성되지 않아야 합니다.
 */
@ExtendWith(MockitoExtension.class)
class GroupMembershipServiceTest {

    @Mock UserRepository userRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMembershipRepository membershipRepository;
    @Mock GroupJoinRestrictionRepository restrictionRepository;
    @Mock GroupInviteLinkRepository inviteLinkRepository;
    @Mock GroupAdminChangeRepository adminChangeRepository;
    @Mock GroupSeasonRepository groupSeasonRepository;
    @Mock GroupCreatureRepository groupCreatureRepository;
    @Mock SeasonGroupScoreRepository groupScoreRepository;
    @Mock SeasonQueryService seasonQueryService;

    private GroupMembershipService service;
    private User user;
    private MutableClock clock;

    /**
     * 매 테스트마다 동일한 기준 시각을 가진 Service를 구성합니다.
     * 시즌 조회는 SeasonRepository 대신 SeasonQueryService Mock으로 대체합니다.
     * 각 테스트는 독립된 Mockito 호출 기록을 사용합니다.
     */
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
                groupSeasonRepository,
                groupCreatureRepository,
                groupScoreRepository,
                seasonQueryService,
                clock,
                "http://localhost:5173"
        );
        user = mock(User.class);
    }

    /**
     * 유효한 시즌에서 그룹 생성이 정상 완료되는지 검증합니다.
     * 그룹·관리자 멤버십·초대 링크·시즌 참여·알·점수가 생성되어야 합니다.
     * 해당 업무가 공통 시즌 조회 Service를 거치는지도 확인합니다.
     */
    @Test
    void createGroup은_그룹_초대_시즌_알_점수를_같은_업무흐름에서_생성한다() {
        Season season = mock(Season.class);
        when(seasonQueryService.findCurrentSeasonForShare()).thenReturn(season);
        when(season.isActiveAt(any(Instant.class))).thenReturn(true);

        // 정상적인 그룹 생성 흐름에 필요한 저장소 응답을 준비합니다.
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(groupRepository.existsByNormalizedName("새싹모임")).thenReturn(false);
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupSeasonRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("새싹모임", "함께 걸어요")
        );

        // 생성 결과와 연관 데이터 저장 요청을 검증합니다.
        assertThat(result.group().group().name()).isEqualTo("새싹모임");
        assertThat(result.initialInvitationUrl()).startsWith("http://localhost:5173/invite/");
        verify(seasonQueryService).findCurrentSeasonForShare();
        verify(inviteLinkRepository).save(any());
        verify(adminChangeRepository).save(any());
        verify(groupCreatureRepository).save(any());
        verify(groupScoreRepository).save(any());
    }

    /**
     * 그룹 이름을 정규화한 결과가 중복이면 생성을 거절합니다.
     * 시즌 유효성을 먼저 검사한 뒤 그룹 이름의 중복을 판정해야 합니다.
     * 기존 GROUP_NAME_ALREADY_USED 업무 오류 동작을 보존합니다.
     */
    @Test
    void createGroup은_정규화한_이름이_중복이면_거절한다() {
        Season season = mock(Season.class);
        when(seasonQueryService.findCurrentSeasonForShare()).thenReturn(season);
        when(season.isActiveAt(any(Instant.class))).thenReturn(true);

        // 유효한 시즌이어도 중복 이름은 등록할 수 없습니다.
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(groupRepository.existsByNormalizedName("중복그룹")).thenReturn(true);

        assertThatThrownBy(() -> service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("중복그룹", "")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 그룹 이름입니다.");
    }

    /**
     * 시즌 조회 도중 종료 시각을 지났다면 그룹 생성을 차단합니다.
     * 시즌 조회 직후 Clock을 경계 시각으로 이동시켜 경합을 재현합니다.
     * 변경된 정책에 따라 404가 아닌 409 코드를 검증합니다.
     */
    @Test
    void createGroup은_시즌_잠금_대기_중_종료_시각이_지나면_그룹을_생성하지_않는다() {
        Season season = mock(Season.class);
        Instant endsAt = Instant.parse("2026-11-03T00:00:01Z");

        // 조회 완료 시점에는 이미 시즌 종료 경계에 도달했습니다.
        when(seasonQueryService.findCurrentSeasonForShare())
                .thenAnswer(invocation -> {
                    clock.set(endsAt);
                    return season;
                });
        when(season.isActiveAt(endsAt)).thenReturn(false);

        assertThatThrownBy(() -> service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("경계그룹", "")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo("CONCURRENT_MODIFICATION"));

        // 만료된 시즌에서는 사용자 잠금이나 그룹 INSERT까지 진행하지 않습니다.
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(groupRepository, never()).save(any());
        verify(groupSeasonRepository, never()).save(any());
    }

    /**
     * 현재 시즌이 없는 경우 그룹 생성을 거절합니다.
     * SeasonQueryService가 던진 409 업무 오류를 변경 없이 전달해야 합니다.
     * 사용자 잠금과 그룹·시즌 참여 생성이 시작되지 않음을 확인합니다.
     */
    @Test
    void createGroup은_시즌이_준비되지_않으면_409를_반환한다() {
        when(seasonQueryService.findCurrentSeasonForShare())
                .thenThrow(new BusinessException(
                        ErrorCode.CONCURRENT_MODIFICATION,
                        "현재 시즌이 준비되지 않았습니다."
                ));

        // SCHEDULED·CLOSING·시즌 미존재 상태 모두 같은 오류 정책을 따릅니다.
        assertThatThrownBy(() -> service.createGroup(
                new LoginUser(1L, "새싹지기", Set.of("USER")),
                new CreateGroupRequest("새그룹", "")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo("CONCURRENT_MODIFICATION"));

        verify(userRepository, never()).findByIdForUpdate(any());
        verify(groupRepository, never()).save(any());
        verify(groupSeasonRepository, never()).save(any());
    }

    /**
     * 사용자와 대상 그룹을 잠근 뒤 일반 회원으로 가입합니다.
     * 가입 결과에 그룹 ID, 역할, 상태 및 닉네임이 반영되어야 합니다.
     * 그룹 인원 증가 갱신 시 공통 Clock 값이 사용되는지도 확인합니다.
     */
    @Test
    void joinGroup은_사용자와_그룹을_잠근_뒤_일반_구성원을_등록한다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        when(group.getStatus()).thenReturn("ACTIVE");
        when(group.getVisibility()).thenReturn("PUBLIC");
        when(groupRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(group));
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);
        when(groupRepository.incrementMemberCountIfAvailable(any(), any())).thenReturn(1);
        when(membershipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 정상 가입 요청의 결과를 확인합니다.
        var result = service.joinGroup(new LoginUser(1L, "가입자", Set.of()), 10L);

        assertThat(result.groupId()).isEqualTo(10L);
        assertThat(result.role()).isEqualTo("MEMBER");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.nickname()).isEqualTo("가입자");
        verify(groupRepository).incrementMemberCountIfAvailable(10L, clock.instant());
    }

    /**
     * 정원이 가득 찬 그룹의 가입 요청을 거절합니다.
     * 그룹과 사용자의 상태를 확인한 뒤 인원 갱신에 실패한 상황입니다.
     * 회원 멤버십이 새로 저장되지 않아야 합니다.
     */
    @Test
    void joinGroup은_잠금_후_정원이_가득_찬_그룹을_거절한다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        Group group = mock(Group.class);
        when(group.getStatus()).thenReturn("ACTIVE");
        when(group.getVisibility()).thenReturn("PUBLIC");
        when(groupRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(group));
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(false);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);

        // 인원 증가가 실패하면 GROUP_FULL 오류가 반환됩니다.
        assertThatThrownBy(() -> service.joinGroup(
                new LoginUser(1L, "가입자", Set.of()), 10L
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo("GROUP_FULL"));

        verify(membershipRepository, never()).save(any());
    }

    /**
     * 초대 가입 시 최종 초대 유효성을 다시 검사합니다.
     * 그룹 잠금 이후 링크가 무효화된 상황을 재현합니다.
     * 초대 가입은 실패하고 그룹 인원은 증가하지 않아야 합니다.
     */
    @Test
    void 초대_가입은_사용자와_그룹_잠금_뒤_초대_유효성을_최종_재검사한다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        when(group.getStatus()).thenReturn("ACTIVE");
        when(group.getVisibility()).thenReturn("PUBLIC");
        when(groupRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(group));
        GroupInviteLink invitation = mock(GroupInviteLink.class);
        when(invitation.getGroup()).thenReturn(group);
        when(inviteLinkRepository.findByIdAndStatus(20L, "ACTIVE"))
                .thenReturn(Optional.of(invitation));
        when(inviteLinkRepository.findActiveForUpdate(20L, 10L))
                .thenReturn(Optional.empty());

        // 가입의 최종 단계에서 무효 초대가 발견됩니다.
        var result = service.tryJoinInvitation(1L, "가입자", 20L);

        assertThat(result.joined()).isFalse();
        assertThat(result.failureCode()).isEqualTo("INVITE_INVALID");
        verify(groupRepository, never()).incrementMemberCountIfAvailable(any(), any());
    }

    /**
     * 기존 가입자도 공개 그룹 목록을 조회할 수 있습니다.
     * 이미 가입된 사용자에게는 가입 불가 사유가 표시되어야 합니다.
     * 검색·페이징 동작의 기존 단위 테스트를 유지합니다.
     */
    @Test
    void 공개_그룹_목록은_이미_가입한_사용자도_조회하고_차단_사유를_표시한다() {
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        when(group.getName()).thenReturn("공개그룹");
        when(group.getDescription()).thenReturn("함께 걸어요");
        when(group.getCurrentMemberCount()).thenReturn(5);
        when(group.getCapacity()).thenReturn(20);
        when(membershipRepository.existsByUserIdAndStatus(1L, "ACTIVE")).thenReturn(true);
        when(restrictionRepository.existsByUserIdAndRestrictedUntilAfter(any(), any())).thenReturn(false);
        when(groupRepository.findPublicGroups(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(group)));

        // 이미 가입한 사용자에게는 목록을 보여주되 가입은 막습니다.
        var result = service.listPublicGroups(
                new LoginUser(1L, "구성원", Set.of("MEMBER")), "공개", 0, 20
        );

        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.joinable()).isFalse();
            assertThat(summary.joinBlockedReason()).isEqualTo("ALREADY_IN_GROUP");
        });
    }

    /**
     * 경계 시각을 변경할 수 있는 테스트 전용 Clock입니다.
     * 운영 서비스는 같은 Clock 인터페이스를 사용합니다.
     * 테스트는 실제 시간 경과를 기다리지 않습니다.
     */
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
