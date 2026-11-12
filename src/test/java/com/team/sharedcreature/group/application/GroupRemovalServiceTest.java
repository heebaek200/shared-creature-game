
package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupJoinRestriction;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.ModerationAction;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.group.infrastructure.ModerationActionRepository;
import com.team.sharedcreature.season.application.SeasonContributionMembershipService;
import com.team.sharedcreature.season.application.SeasonQueryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STEP 5 비활동 회원 강퇴의 Service 단위 테스트입니다.
 *
 * 실제 도메인 객체의 상태 변화를 검증합니다.
 * DB Repository는 Mockito로 대체합니다.
 * 비활동 경계는 고정 Clock을 사용해 재현합니다.
 */
@ExtendWith(MockitoExtension.class)
class GroupRemovalServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-11-05T04:00:00Z");

    @Mock UserRepository userRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMembershipRepository membershipRepository;
    @Mock GroupJoinRestrictionRepository restrictionRepository;
    @Mock ModerationActionRepository moderationActionRepository;
    @Mock SeasonQueryService seasonQueryService;
    @Mock SeasonContributionMembershipService contributionMembershipService;

    private GroupRemovalService service;

    /**
     * 테스트 대상 Service를 고정 시각으로 생성합니다.
     *
     * Spring Context를 실행하지 않아 테스트 속도를 줄입니다.
     * 모든 테스트에서 동일한 현재 시각을 사용합니다.
     * 각 테스트의 Entity는 별도의 Fixture로 준비합니다.
     */
    @BeforeEach
    void setUp() {
        service = new GroupRemovalService(
                userRepository,
                groupRepository,
                membershipRepository,
                restrictionRepository,
                moderationActionRepository,
                seasonQueryService,
                contributionMembershipService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    /**
     * 73시간 미접속한 MEMBER의 강퇴가 정상 처리되는지 검증합니다.
     *
     * 멤버십 종료와 인원 감소, 제한 기간을 확인합니다.
     * 별도의 강퇴 감사 기록도 생성되어야 합니다.
     * 과거 가입 정보는 삭제되지 않아야 합니다.
     */
    @Test
    void 비활동_MEMBER_강퇴는_상태_제한_감사이력을_기록한다() {
        Fixture f = fixture(Duration.ofHours(73));

        // 최초 강퇴이므로 기존 가입 제한 기록은 없습니다.
        when(restrictionRepository.findByUserIdForUpdate(2L))
                .thenReturn(Optional.empty());

        var result = service.removeInactiveMember(
                1L, 2L, "장기간 미접속"
        );

        // 멤버십과 그룹 상태가 함께 변경돼야 합니다.
        assertThat(result.exitReason()).isEqualTo("INACTIVE");
        assertThat(result.groupDeactivated()).isFalse();
        assertThat(result.restrictionUntil())
                .isEqualTo(NOW.plus(Duration.ofHours(24)));

        assertThat(f.targetMembership().getStatus()).isEqualTo("REMOVED");
        assertThat(f.targetMembership().getExitReason()).isEqualTo("INACTIVE");
        assertThat(f.targetMembership().getEndedByUser()).isSameAs(f.actor());
        assertThat(f.group().getCurrentMemberCount()).isEqualTo(1);

        // 제한 기록과 강퇴 감사 기록이 함께 생성되어야 합니다.
        verify(restrictionRepository).save(any(GroupJoinRestriction.class));

        ArgumentCaptor<ModerationAction> captor =
                ArgumentCaptor.forClass(ModerationAction.class);

        verify(moderationActionRepository).save(captor.capture());
        assertThat(captor.getValue().getActorUser()).isSameAs(f.actor());
        assertThat(captor.getValue().getTargetUser()).isSameAs(f.target());
        assertThat(captor.getValue().getActionType()).isEqualTo("REMOVE_INACTIVE");
        assertThat(captor.getValue().getReason()).isEqualTo("장기간 미접속");
    }

    /**
     * 정확히 72시간 10분이 지난 사용자는 강퇴할 수 있어야 합니다.
     *
     * 경계 시각을 포함하는 이상 조건을 검증합니다.
     * 하루 가입 제한도 동일한 강퇴 확정 시각을 사용합니다.
     * 밀리초 단위의 잘못된 경계 판정 회귀를 방지합니다.
     */
    @Test
    void 정확히_72시간_10분_경과하면_강퇴할_수_있다() {
        Fixture f = fixture(Duration.ofHours(72).plusMinutes(10));

        // 제한 기록이 없는 최초 강퇴 조건입니다.
        when(restrictionRepository.findByUserIdForUpdate(2L))
                .thenReturn(Optional.empty());

        var result = service.removeInactiveMember(1L, 2L, "기준 도달");

        // 경계 시각과 같아도 성공해야 합니다.
        assertThat(result.endedAt()).isEqualTo(NOW);
        assertThat(f.targetMembership().getStatus()).isEqualTo("REMOVED");
        verify(moderationActionRepository).save(any(ModerationAction.class));
    }

    /**
     * 비활동 조건보다 1초 부족하면 강퇴를 거부합니다.
     *
     * 관리자 권한은 정상이라고 가정합니다.
     * 멤버십과 그룹 인원은 변경되지 않아야 합니다.
     * 실패한 요청의 제한 기록과 감사 이력은 생성하지 않습니다.
     */
    @Test
    void 기준보다_1초_부족하면_409로_거부한다() {
        Fixture f = fixture(
                Duration.ofHours(72).plusMinutes(10).minusSeconds(1)
        );

        // 비활동 조건 검사에서 거부되어야 합니다.
        assertThatThrownBy(() ->
                service.removeInactiveMember(1L, 2L, "기간 부족")
        ).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code())
                        .isEqualTo("INACTIVITY_REQUIREMENT_NOT_MET")
        );

        // 거부 요청에서는 데이터 변경을 수행하지 않습니다.
        assertThat(f.targetMembership().getStatus()).isEqualTo("ACTIVE");
        assertThat(f.group().getCurrentMemberCount()).isEqualTo(2);

        verify(restrictionRepository, never()).save(any());
        verify(moderationActionRepository, never()).save(any());
    }

    /**
     * 두 사용자와 그룹의 정상 활성 상태를 준비합니다.
     *
     * 실행자는 PRIMARY이고 대상은 MEMBER로 구성합니다.
     * 실제 도메인 Entity를 생성한 뒤 테스트용 PK를 부여합니다.
     * Repository의 사전 조회·쓰기 잠금 응답은 동일한 Entity를 반환합니다.
     */
    private Fixture fixture(Duration inactivity) {
        User actor = User.register("USER", NOW);
        User target = User.register("USER", NOW);

        // DB가 없는 테스트이므로 엔티티 식별자를 직접 설정합니다.
        ReflectionTestUtils.setField(actor, "id", 1L);
        ReflectionTestUtils.setField(target, "id", 2L);
        ReflectionTestUtils.setField(
                target, "lastActiveAt", NOW.minus(inactivity)
        );

        Group group = Group.create(
                "테스트그룹", "테스트그룹", "", actor, NOW
        );
        ReflectionTestUtils.setField(group, "id", 10L);
        ReflectionTestUtils.setField(group, "currentMemberCount", 2);

        // 실제 도메인 팩토리로 역할과 멤버십을 구성합니다.
        GroupMembership actorMembership =
                GroupMembership.primary(group, actor, NOW);
        GroupMembership targetMembership =
                GroupMembership.member(group, target, NOW);

        ReflectionTestUtils.setField(actorMembership, "id", 101L);
        ReflectionTestUtils.setField(targetMembership, "id", 102L);

        // Service의 사전 조회와 비관적 잠금 결과를 연결합니다.
        when(membershipRepository.findActiveGroupIdByUserId(1L))
                .thenReturn(Optional.of(10L));

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(actor));
        when(userRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(target));

        when(groupRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(group));

        when(membershipRepository.findActiveMembershipId(10L, 1L))
                .thenReturn(Optional.of(101L));
        when(membershipRepository.findActiveMembershipId(10L, 2L))
                .thenReturn(Optional.of(102L));

        when(membershipRepository.findByIdForUpdate(101L))
                .thenReturn(Optional.of(actorMembership));
        when(membershipRepository.findByIdForUpdate(102L))
                .thenReturn(Optional.of(targetMembership));

        return new Fixture(
                actor, target, group, actorMembership, targetMembership
        );
    }

    private record Fixture(
            User actor,
            User target,
            Group group,
            GroupMembership actorMembership,
            GroupMembership targetMembership
    ) {}
}
