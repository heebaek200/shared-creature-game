package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.domain.GroupJoinRestriction;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupAdminChangeRepository;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 그룹 자진 탈퇴의 업무 규칙을 DB 없이 검증합니다.
 * 실제 도메인 엔티티의 상태 변경과 Mockito Repository 호출을 함께 확인합니다.
 * 트랜잭션 롤백, DB UNIQUE 제약, 병렬 잠금은 통합 테스트의 책임입니다.
 */
@ExtendWith(MockitoExtension.class)
class GroupDepartureServiceTest {
    private static final long USER_ID = 1L;
    private static final long GROUP_ID = 10L;
    private static final long MEMBERSHIP_ID = 101L;
    private static final Instant JOINED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");

    @Mock private UserRepository userRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMembershipRepository membershipRepository;
    @Mock private GroupJoinRestrictionRepository restrictionRepository;
    @Mock private GroupInviteLinkRepository inviteLinkRepository;
    @Mock private GroupAdminChangeRepository adminChangeRepository;
    @Mock private GroupSeasonRepository groupSeasonRepository;
    @Mock private GroupCreatureRepository groupCreatureRepository;
    @Mock private SeasonGroupScoreRepository groupScoreRepository;
    @Mock private SeasonQueryService seasonQueryService;

    private GroupMembershipService service;

    /**
     * 테스트마다 고정 UTC 시각과 Service 협력 객체를 초기화합니다.
     * Repository와 SeasonQueryService는 Mockito Mock으로 대체합니다.
     * 생성자 매개변수는 커밋 7488b1f의 실제 시그니처 순서를 따릅니다.
     */
    @BeforeEach
    void setUp() {
        // 실제 GroupMembershipService 생성자에서 정의한 순서대로 주입합니다.
        service = new GroupMembershipService(
                userRepository, groupRepository, membershipRepository,
                restrictionRepository, inviteLinkRepository, adminChangeRepository,
                groupSeasonRepository, groupCreatureRepository, groupScoreRepository,
                seasonQueryService, Clock.fixed(NOW, ZoneOffset.UTC),
                "http://localhost:5173"
        );
    }

    /**
     * 일반 회원 탈퇴는 멤버십을 LEFT로 종료하고 인원을 감소시킵니다.
     * 최초 탈퇴자의 가입 제한을 한 건 생성해야 합니다.
     * 제한 만료 시각은 종료 시각으로부터 정확히 24시간 뒤여야 합니다.
     */
    @Test
    void 일반_회원_탈퇴는_멤버십과_인원_가입제한을_갱신한다() {
        Fixture fixture = arrange("MEMBER", 3);

        // 실제 도메인 객체의 변경 결과를 검증합니다.
        var response = service.leaveMyGroup(USER_ID);
        assertThat(response.groupId()).isEqualTo(GROUP_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.endedAt()).isEqualTo(NOW);
        assertThat(response.exitReason()).isEqualTo("SELF");
        assertThat(response.restrictionUntil()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(response.groupDeactivated()).isFalse();

        assertThat(fixture.membership().getStatus()).isEqualTo("LEFT");
        assertThat(fixture.membership().getRole()).isEqualTo("MEMBER");
        assertThat(fixture.membership().getEndedAt()).isEqualTo(NOW);
        assertThat(fixture.membership().getEndedByUser()).isSameAs(fixture.actor());
        assertThat(fixture.membership().getExitReason()).isEqualTo("SELF");
        assertThat(fixture.group().getCurrentMemberCount()).isEqualTo(2);
        assertThat(fixture.group().getStatus()).isEqualTo("ACTIVE");

        // 제한 기록이 회원·멤버십·만료 시각을 정확히 참조해야 합니다.
        ArgumentCaptor<GroupJoinRestriction> captor = ArgumentCaptor.forClass(GroupJoinRestriction.class);
        verify(restrictionRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(fixture.actor());
        assertThat(captor.getValue().getSourceMembership()).isSameAs(fixture.membership());
        assertThat(captor.getValue().getReason()).isEqualTo("SELF");
        assertThat(captor.getValue().getRestrictedFrom()).isEqualTo(NOW);
        assertThat(captor.getValue().getRestrictedUntil()).isEqualTo(NOW.plusSeconds(86_400));
        verifyNoInteractions(inviteLinkRepository, groupSeasonRepository, groupCreatureRepository);
    }

    /**
     * 부 관리자는 별도의 PRIMARY 이전 없이 자진 탈퇴할 수 있습니다.
     * 탈퇴하더라도 역사적 멤버십 역할은 DEPUTY로 보존합니다.
     * 그룹 자체는 활성 상태를 유지하고 인원만 한 명 줄어듭니다.
     */
    @Test
    void 부관리자도_자진_탈퇴할_수_있다() {
        Fixture fixture = arrange("DEPUTY", 2);

        // MEMBER와 동일한 종료 규칙이 적용되는지 확인합니다.
        var response = service.leaveMyGroup(USER_ID);
        assertThat(response.groupDeactivated()).isFalse();
        assertThat(fixture.membership().getRole()).isEqualTo("DEPUTY");
        assertThat(fixture.membership().getStatus()).isEqualTo("LEFT");
        assertThat(fixture.group().getCurrentMemberCount()).isEqualTo(1);
        assertThat(fixture.group().getPrimaryAdminUser().getId()).isEqualTo(2L);
        verify(restrictionRepository).save(any(GroupJoinRestriction.class));
    }

    /**
     * 기존에 탈퇴 이력이 있는 사용자는 가입 제한 행을 재사용합니다.
     * 사용자별 UNIQUE 제약을 고려해 두 번째 행을 만들면 안 됩니다.
     * 제한 근거와 시작·종료 시각만 새로운 탈퇴 정보로 갱신합니다.
     */
    @Test
    void 재탈퇴는_가입제한을_중복_생성하지_않고_갱신한다() {
        Fixture fixture = arrange("MEMBER", 2);
        Instant previous = JOINED.minusSeconds(10_000);
        GroupMembership previousMembership = GroupMembership.member(
                fixture.group(), fixture.actor(), previous);
        GroupJoinRestriction restriction = GroupJoinRestriction.forDeparture(
                fixture.actor(), previousMembership, "SELF", previous);
        when(restrictionRepository.findByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(restriction));

        // 새로운 탈퇴 사실로 같은 행의 값을 갱신합니다.
        service.leaveMyGroup(USER_ID);
        assertThat(restriction.getCreatedAt()).isEqualTo(previous);
        assertThat(restriction.getSourceMembership()).isSameAs(fixture.membership());
        assertThat(restriction.getRestrictedFrom()).isEqualTo(NOW);
        assertThat(restriction.getRestrictedUntil()).isEqualTo(NOW.plusSeconds(86_400));
        verify(restrictionRepository, never()).save(any(GroupJoinRestriction.class));
    }

    /**
     * 다른 활성 구성원이 있는 PRIMARY는 탈퇴할 수 없습니다.
     * 서비스가 PRIMARY_TRANSFER_REQUIRED를 반환해야 합니다.
     * 실패한 요청은 멤버십·그룹·가입 제한을 변경하면 안 됩니다.
     */
    @Test
    void 다른_회원이_남은_PRIMARY_탈퇴는_이전을_요구한다() {
        Fixture fixture = arrange("PRIMARY", 3);

        // PRIMARY 이전 없이 탈퇴를 요청합니다.
        assertThatThrownBy(() -> service.leaveMyGroup(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PRIMARY_TRANSFER_REQUIRED"));
        assertThat(fixture.membership().getStatus()).isEqualTo("ACTIVE");
        assertThat(fixture.group().getStatus()).isEqualTo("ACTIVE");
        assertThat(fixture.group().getCurrentMemberCount()).isEqualTo(3);
        verifyNoInteractions(restrictionRepository, groupSeasonRepository, groupCreatureRepository);
    }

    /**
     * 마지막 PRIMARY의 탈퇴는 그룹 관련 상태를 모두 종료합니다.
     * 초대 링크 무효화, 현재 시즌 종료, 크리처 보관을 검증합니다.
     * 경험치나 성장 단계 등 크리처의 역사적 속성은 보존합니다.
     */
    @Test
    void 마지막_PRIMARY_탈퇴는_그룹_초대_시즌_크리처를_종료한다() {
        Fixture fixture = arrange("PRIMARY", 1);
        GroupInviteLink invitation = GroupInviteLink.issue(
                fixture.group(), "a".repeat(64), fixture.actor(), JOINED);
        when(inviteLinkRepository.findActiveByGroupIdForUpdate(GROUP_ID))
                .thenReturn(Optional.of(invitation));

        // 정상적인 현재 시즌과 크리처를 준비합니다.
        GroupSeason groupSeason = GroupSeason.start(
                fixture.group(), mock(Season.class), JOINED);
        ReflectionTestUtils.setField(groupSeason, "id", 55L);
        GroupCreature creature = GroupCreature.egg(groupSeason, JOINED);
        when(groupSeasonRepository.findOpenByGroupIdForUpdate(GROUP_ID))
                .thenReturn(Optional.of(groupSeason));
        when(groupCreatureRepository.findForUpdateByGroupSeasonId(55L))
                .thenReturn(Optional.of(creature));
        long oldExperience = creature.getTotalExperience();
        String oldGrowthStage = creature.getGrowthStage();

        // 그룹 종료 결과와 관련 엔티티의 상태 전이를 함께 확인합니다.
        var response = service.leaveMyGroup(USER_ID);
        assertThat(response.groupDeactivated()).isTrue();
        assertThat(fixture.group().getStatus()).isEqualTo("INACTIVE");
        assertThat(fixture.group().getCurrentMemberCount()).isZero();
        assertThat(fixture.group().getPrimaryAdminUser()).isNull();
        assertThat(fixture.membership().getStatus()).isEqualTo("LEFT");
        assertThat(invitation.getStatus()).isEqualTo("INVALIDATED");
        assertThat(invitation.getInvalidatedAt()).isEqualTo(NOW);
        assertThat(groupSeason.getStatus()).isEqualTo("ENDED");
        assertThat(creature.getStatus()).isEqualTo("ARCHIVED");
        assertThat(creature.getTotalExperience()).isEqualTo(oldExperience);
        assertThat(creature.getGrowthStage()).isEqualTo(oldGrowthStage);
    }

    /**
     * 활성 초대가 없는 마지막 PRIMARY도 탈퇴할 수 있어야 합니다.
     * 현재 시즌 참여가 이미 없다면 그룹 종료만 수행합니다.
     * 과거 종료 이력을 새로 생성하거나 수정하지 않습니다.
     */
    @Test
    void 마지막_PRIMARY는_초대나_진행중_시즌이_없어도_탈퇴한다() {
        Fixture fixture = arrange("PRIMARY", 1);
        when(groupSeasonRepository.findOpenByGroupIdForUpdate(GROUP_ID))
                .thenReturn(Optional.empty());

        // 선택적 부가 데이터가 없어도 탈퇴는 성공해야 합니다.
        var response = service.leaveMyGroup(USER_ID);
        assertThat(response.groupDeactivated()).isTrue();
        assertThat(fixture.group().getStatus()).isEqualTo("INACTIVE");
        verifyNoInteractions(groupCreatureRepository);
    }

    /**
     * 탈퇴 전 이미 종료된 멤버십을 발견하면 중복 처리를 거부합니다.
     * 잠금 획득 후 최신 상태를 다시 검사하는 로직을 검증합니다.
     * 실패한 요청은 제한 기록 생성과 인원 변경으로 진행되지 않습니다.
     */
    @Test
    void 잠금_후_멤버십이_종료됐으면_탈퇴를_거부한다() {
        Fixture fixture = arrange("MEMBER", 2);
        fixture.membership().leaveBySelf(fixture.actor(), NOW.minusSeconds(100));

        // 사전 그룹 조회가 성공해도 최종 멤버십 검증에서 차단합니다.
        assertThatThrownBy(() -> service.leaveMyGroup(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThat(fixture.group().getCurrentMemberCount()).isEqualTo(2);
        verifyNoInteractions(restrictionRepository);
    }

    /**
     * 활성 그룹을 찾지 못하면 어떤 DB 변경도 시도하지 않습니다.
     * 중복 탈퇴나 잘못된 상태의 인증 요청을 재처리하지 않습니다.
     * ROLE_FORBIDDEN 업무 코드가 유지되어야 합니다.
     */
    @Test
    void 활성_그룹이_없으면_ROLE_FORBIDDEN으로_거부한다() {
        when(membershipRepository.findActiveGroupIdByUserId(USER_ID))
                .thenReturn(Optional.empty());

        // 쓰기 잠금이나 저장 작업 없이 바로 종료합니다.
        assertThatThrownBy(() -> service.leaveMyGroup(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ROLE_FORBIDDEN"));
        verifyNoInteractions(userRepository, groupRepository, restrictionRepository);
    }

    /**
     * 활성 멤버십이 1건도 없는 그룹에서 MEMBER 탈퇴를 허용하지 않습니다.
     * 마지막 1명의 역할은 반드시 PRIMARY여야 하는 설계 제약입니다.
     * 손상된 인원·역할 조합을 감지해 더 큰 데이터 불일치를 방지합니다.
     */
    @Test
    void 마지막_구성원이_MEMBER라면_정합성_오류로_거부한다() {
        Fixture fixture = arrange("MEMBER", 1);

        // 그룹 인원을 0으로 감소시키거나 비활성화해서는 안 됩니다.
        assertThatThrownBy(() -> service.leaveMyGroup(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThat(fixture.membership().getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(restrictionRepository, groupSeasonRepository);
    }

    /**
     * 그룹 행 잠금 이후 비활성 상태가 확인되면 탈퇴를 거부합니다.
     * 사전 조회가 낡은 데이터를 읽은 경우를 재현합니다.
     * 잘못된 상태에서 가입 제한을 만들거나 멤버십을 종료하지 않습니다.
     */
    @Test
    void 잠금_후_그룹이_비활성이면_탈퇴를_거부한다() {
        Fixture fixture = arrange("MEMBER", 2);
        ReflectionTestUtils.setField(fixture.group(), "status", "INACTIVE");

        // 그룹이 종료됐으므로 멤버십까지 잠그기 전에 차단합니다.
        assertThatThrownBy(() -> service.leaveMyGroup(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CONCURRENT_MODIFICATION"));
        verifyNoInteractions(restrictionRepository);
    }

    /**
     * Service의 잠금 조회 순서가 기존 그룹 관리 정책과 일치하는지 확인합니다.
     * 사용자 → 그룹 → 멤버십 ID 조회 → 멤버십 잠금을 실행해야 합니다.
     * 동시성 안전성 자체는 DB 잠금 통합 테스트로 검증해야 합니다.
     */
    @Test
    void 탈퇴_명령은_정해진_순서로_잠금을_획득한다() {
        arrange("MEMBER", 3);
        org.mockito.InOrder inOrder = inOrder(
                userRepository, groupRepository, membershipRepository, restrictionRepository);

        // 인원 감소 이전에 사용자와 그룹의 락을 잡습니다.
        service.leaveMyGroup(USER_ID);
        inOrder.verify(membershipRepository).findActiveGroupIdByUserId(USER_ID);
        inOrder.verify(userRepository).findByIdForUpdate(USER_ID);
        inOrder.verify(groupRepository).findByIdForUpdate(GROUP_ID);
        inOrder.verify(membershipRepository).findActiveMembershipId(GROUP_ID, USER_ID);
        inOrder.verify(membershipRepository).findByIdForUpdate(MEMBERSHIP_ID);
        inOrder.verify(restrictionRepository).findByUserIdForUpdate(USER_ID);
    }

    /**
     * 사용자 역할별로 실제 멤버십과 그룹 상태를 구성합니다.
     * Repository는 실제 생성한 엔티티를 그대로 반환하도록 연결합니다.
     * ReflectionTestUtils는 DB 생성 ID와 회원 수 설정에만 사용합니다.
     *
     * @param role 요청자의 MEMBER, DEPUTY, PRIMARY 중 하나
     * @param memberCount 현재 그룹의 활성 회원 수
     * @return 검증에 사용할 엔티티 묶음
     */
    private Fixture arrange(String role, int memberCount) {
        // 고정 사용자 ID로 Service의 조회 조건을 통과시킵니다.
        User actor = User.register("USER", JOINED);
        ReflectionTestUtils.setField(actor, "id", USER_ID);
        User primary = "PRIMARY".equals(role) ? actor : User.register("USER", JOINED);
        if (primary != actor) {
            ReflectionTestUtils.setField(primary, "id", 2L);
        }

        // 그룹·멤버십의 상태는 실제 도메인 팩토리로 생성합니다.
        Group group = Group.create("테스트그룹", "테스트그룹", "", primary, JOINED);
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        ReflectionTestUtils.setField(group, "currentMemberCount", memberCount);
        GroupMembership membership = "PRIMARY".equals(role)
                ? GroupMembership.primary(group, actor, JOINED)
                : GroupMembership.member(group, actor, JOINED);
        if ("DEPUTY".equals(role)) {
            membership.changeDeputyRole("DEPUTY", JOINED);
        }
        ReflectionTestUtils.setField(membership, "id", MEMBERSHIP_ID);

        // Service가 사용하는 사전 조회와 쓰기 잠금 결과를 준비합니다.
        when(membershipRepository.findActiveGroupIdByUserId(USER_ID))
                .thenReturn(Optional.of(GROUP_ID));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(actor));
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        lenient().when(membershipRepository.findActiveMembershipId(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(MEMBERSHIP_ID));
        lenient().when(membershipRepository.findByIdForUpdate(MEMBERSHIP_ID))
                .thenReturn(Optional.of(membership));

        return new Fixture(actor, group, membership);
    }

    private record Fixture(User actor, Group group, GroupMembership membership) {
    }
}
