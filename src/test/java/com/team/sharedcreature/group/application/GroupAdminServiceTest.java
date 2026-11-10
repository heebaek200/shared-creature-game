package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.MyGroupResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupAdminChange;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupAdminChangeRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import jakarta.persistence.EntityManager;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * STEP 3 관리자 권한 변경의 빠른 회귀 테스트입니다.
 *
 * 실제 도메인 객체를 사용해 역할 변경과 관리자 참조의 상태 변화를 검증합니다.
 * 저장소·EntityManager만 대체하며 실제 DB의 UNIQUE 제약은 통합 테스트에서 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class GroupAdminServiceTest {

    private static final long ACTOR_ID = 1L;
    private static final long TARGET_ID = 2L;
    private static final long GROUP_ID = 10L;
    private static final long ACTOR_MEMBERSHIP_ID = 101L;
    private static final long TARGET_MEMBERSHIP_ID = 102L;
    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");

    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMembershipRepository membershipRepository;
    @Mock private GroupAdminChangeRepository adminChangeRepository;
    @Mock private GroupQueryService groupQueryService;
    @Mock private EntityManager entityManager;

    private GroupAdminService service;

    /**
     * 실제 서비스 생성자와 같은 의존성 순서로 테스트 대상을 준비합니다.
     *
     * 고정 시각을 사용해 생성 이력과 도메인 수정 시각을 재현 가능하게 만듭니다.
     * 데이터베이스의 상태는 각각의 테스트에서 별도 Fixture로 구성합니다.
     */
    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new GroupAdminService(
                userRepository,
                userProfileRepository,
                groupRepository,
                membershipRepository,
                adminChangeRepository,
                groupQueryService,
                entityManager,
                fixedClock
        );
    }

    /**
     * PRIMARY가 일반 회원에게 DEPUTY를 부여하고 감사 이력을 저장합니다.
     *
     * DTO와 대상 멤버십 모두 DEPUTY여야 합니다.
     * 이력에는 실행자와 대상자가 뒤바뀌지 않고 기록되어야 합니다.
     */
    @Test
    void 부관리자_부여는_대상_멤버십과_감사이력을_변경한다() {
        Fixture fixture = arrange("PRIMARY", "MEMBER");
        arrangeTargetProfile(fixture);

        // 실제 도메인 상태와 응답 DTO를 함께 확인합니다.
        var response = service.changeMemberRole(ACTOR_ID, TARGET_ID, "DEPUTY");
        assertThat(response.userId()).isEqualTo(TARGET_ID);
        assertThat(response.role()).isEqualTo("DEPUTY");
        assertThat(fixture.targetMembership().getRole()).isEqualTo("DEPUTY");

        // actor/target 뒤바뀜 회귀를 감사 이력까지 검사합니다.
        ArgumentCaptor<GroupAdminChange> captor = ArgumentCaptor.forClass(GroupAdminChange.class);
        verify(adminChangeRepository).save(captor.capture());
        GroupAdminChange history = captor.getValue();
        assertThat(history.getActorUser().getId()).isEqualTo(ACTOR_ID);
        assertThat(history.getTargetUser().getId()).isEqualTo(TARGET_ID);
        assertThat(history.getChangeType()).isEqualTo("GRANT_DEPUTY");
        assertThat(history.getPreviousRole()).isEqualTo("MEMBER");
        assertThat(history.getNewRole()).isEqualTo("DEPUTY");
    }

    /**
     * PRIMARY가 부 관리자를 일반 회원으로 되돌릴 수 있어야 합니다.
     *
     * 역할은 MEMBER로 변경되고 이전 역할은 DEPUTY로 기록됩니다.
     * 이미 부여했던 역할을 회수하는 정상 흐름을 독립적으로 검증합니다.
     */
    @Test
    void 부관리자_회수는_MEMBER와_회수이력을_기록한다() {
        Fixture fixture = arrange("PRIMARY", "DEPUTY");
        arrangeTargetProfile(fixture);

        // 기존 DEPUTY 권한이 회수되어야 합니다.
        var response = service.changeMemberRole(ACTOR_ID, TARGET_ID, "MEMBER");
        assertThat(response.role()).isEqualTo("MEMBER");
        assertThat(fixture.targetMembership().getRole()).isEqualTo("MEMBER");

        // 이력에서 이전 역할과 다음 역할을 확인합니다.
        ArgumentCaptor<GroupAdminChange> captor = ArgumentCaptor.forClass(GroupAdminChange.class);
        verify(adminChangeRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("REVOKE_DEPUTY");
        assertThat(captor.getValue().getPreviousRole()).isEqualTo("DEPUTY");
        assertThat(captor.getValue().getNewRole()).isEqualTo("MEMBER");
    }

    /**
     * 동일한 역할로 다시 요청하면 상태 변경과 이력 생성을 생략합니다.
     *
     * 응답은 정상적으로 반환하되 불필요한 관리자 감사 기록은 남기지 않습니다.
     * 수정 시각 역시 변경하지 않아야 합니다.
     */
    @Test
    void 동일_역할_재요청은_감사이력과_수정시각을_바꾸지_않는다() {
        Fixture fixture = arrange("PRIMARY", "DEPUTY");
        arrangeTargetProfile(fixture);
        Instant originalUpdatedAt = fixture.targetMembership().getUpdatedAt();

        // 멱등 요청은 동일 상태를 반환합니다.
        var response = service.changeMemberRole(ACTOR_ID, TARGET_ID, "DEPUTY");
        assertThat(response.role()).isEqualTo("DEPUTY");
        assertThat(fixture.targetMembership().getUpdatedAt()).isEqualTo(originalUpdatedAt);
        verify(adminChangeRepository, never()).save(any());
    }

    /**
     * PRIMARY 이전 시 actor와 target이 정확히 구분되는지 검증합니다.
     *
     * 이전 관리자만 MEMBER로 강등되고 대상 회원만 PRIMARY로 승격되어야 합니다.
     * 그룹의 primaryAdmin 참조 및 감사 이력도 대상자를 가리켜야 합니다.
     */
    @Test
    void PRIMARY_이전은_두_멤버십과_그룹_참조를_동시에_바꾼다() {
        Fixture fixture = arrange("PRIMARY", "MEMBER");
        MyGroupResponse expectedResponse = org.mockito.Mockito.mock(MyGroupResponse.class);
        when(groupQueryService.getMyGroup(ACTOR_ID)).thenReturn(expectedResponse);

        // 서비스에 포함된 두 번의 flush 호출과 도메인 상태를 확인합니다.
        var actualResponse = service.transferPrimaryAdmin(ACTOR_ID, TARGET_ID);
        assertThat(actualResponse).isSameAs(expectedResponse);
        assertThat(fixture.actorMembership().getRole()).isEqualTo("MEMBER");
        assertThat(fixture.targetMembership().getRole()).isEqualTo("PRIMARY");
        assertThat(fixture.group().getPrimaryAdminUser().getId()).isEqualTo(TARGET_ID);
        verify(entityManager, times(2)).flush();

        // 감사 이력의 실행자와 대상자가 반대로 저장되지 않았는지도 확인합니다.
        ArgumentCaptor<GroupAdminChange> captor = ArgumentCaptor.forClass(GroupAdminChange.class);
        verify(adminChangeRepository).save(captor.capture());
        assertThat(captor.getValue().getActorUser().getId()).isEqualTo(ACTOR_ID);
        assertThat(captor.getValue().getTargetUser().getId()).isEqualTo(TARGET_ID);
        assertThat(captor.getValue().getChangeType()).isEqualTo("TRANSFER_PRIMARY");
        assertThat(captor.getValue().getPreviousRole()).isEqualTo("MEMBER");
    }

    /**
     * DEPUTY도 주 관리자로 이전받을 수 있는 유효한 대상입니다.
     *
     * DEPUTY 역할을 변경하기 전 값으로 감사 이력에 기록합니다.
     * 기존 PRIMARY가 MEMBER로 바뀌는 것은 동일합니다.
     */
    @Test
    void DEPUTY에게_PRIMARY를_이전하면_이전_역할이_보존된다() {
        Fixture fixture = arrange("PRIMARY", "DEPUTY");

        // 새 PRIMARY 지정과 이전 역할의 기록을 함께 검증합니다.
        service.transferPrimaryAdmin(ACTOR_ID, TARGET_ID);
        assertThat(fixture.actorMembership().getRole()).isEqualTo("MEMBER");
        assertThat(fixture.targetMembership().getRole()).isEqualTo("PRIMARY");
        ArgumentCaptor<GroupAdminChange> captor = ArgumentCaptor.forClass(GroupAdminChange.class);
        verify(adminChangeRepository).save(captor.capture());
        assertThat(captor.getValue().getPreviousRole()).isEqualTo("DEPUTY");
    }

    /**
     * PRIMARY가 아닌 회원은 역할을 변경할 수 없어야 합니다.
     *
     * 서비스는 세션 권한 대신 잠금으로 재조회한 실제 멤버십을 검사합니다.
     * 권한 검증 실패 후에는 감사 이력을 생성하지 않습니다.
     */
    @Test
    void PRIMARY가_아닌_사용자는_역할_변경을_거부한다() {
        arrange("MEMBER", "MEMBER");

        // 그룹의 관리자 참조만으로는 변경 권한을 얻을 수 없습니다.
        assertThatThrownBy(() -> service.changeMemberRole(ACTOR_ID, TARGET_ID, "DEPUTY"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ROLE_FORBIDDEN"));
        verifyNoInteractions(adminChangeRepository);
    }

    /**
     * 비활성 회원에게 PRIMARY 권한을 이전하는 것을 막습니다.
     *
     * 대상 사용자의 최신 상태를 잠근 뒤 검사해야 합니다.
     * 검증 실패한 경우 멤버십과 감사 이력은 수정되지 않습니다.
     */
    @Test
    void 비활성_사용자에게_PRIMARY를_이전할_수_없다() {
        Fixture fixture = arrange("PRIMARY", "MEMBER");
        ReflectionTestUtils.setField(fixture.target(), "status", "SUSPENDED");

        // 대상 사용자가 비활성이면 도메인 변경 전 거부합니다.
        assertThatThrownBy(() -> service.transferPrimaryAdmin(ACTOR_ID, TARGET_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThat(fixture.group().getPrimaryAdminUser().getId()).isEqualTo(ACTOR_ID);
        verifyNoInteractions(adminChangeRepository);
    }

    /**
     * 자기 자신을 이전 대상으로 지정한 요청은 초기 단계에서 거부합니다.
     *
     * 사용자 행을 잠그기 전에 잘못된 요청을 감지합니다.
     * 불필요한 Repository 호출이 발생하지 않아야 합니다.
     */
    @Test
    void 자기_자신에게_PRIMARY_이전은_거부한다() {
        // 사전 검증 단계에서 예외가 발생합니다.
        assertThatThrownBy(() -> service.transferPrimaryAdmin(ACTOR_ID, ACTOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_FIELD_VALUE"));
        verifyNoInteractions(userRepository, groupRepository, membershipRepository);
    }

    /**
     * 역할 변경 요청 값은 MEMBER와 DEPUTY만 허용합니다.
     *
     * PRIMARY를 직접 부여하는 우회 요청은 허용되지 않습니다.
     * 검사 실패 시 사용자 또는 그룹에 잠금을 시도해서도 안 됩니다.
     */
    @Test
    void 역할_변경_API로_PRIMARY를_직접_부여할_수_없다() {
        // PRIMARY는 별도의 전용 이전 API로만 지정할 수 있습니다.
        assertThatThrownBy(() -> service.changeMemberRole(ACTOR_ID, TARGET_ID, "PRIMARY"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_FIELD_VALUE"));
        verifyNoInteractions(userRepository, groupRepository, membershipRepository);
    }

    /**
     * 실제 도메인 객체와 저장소 잠금 결과를 묶어 정상 상태를 구성합니다.
     *
     * 데이터 접근 순서는 사용자 오름차순, 그룹, 멤버십 오름차순입니다.
     * 서로 다른 실제 엔티티를 사용해 actor/target이 바뀌는 회귀를 포착합니다.
     *
     * @param actorRole 실행자의 실제 역할
     * @param targetRole 변경 대상의 실제 역할
     * @return 검증 가능한 도메인 Fixture
     */
    private Fixture arrange(String actorRole, String targetRole) {
        User actor = User.register("USER", NOW);
        User target = User.register("USER", NOW);
        ReflectionTestUtils.setField(actor, "id", ACTOR_ID);
        ReflectionTestUtils.setField(target, "id", TARGET_ID);

        // 주 관리자 사용자 참조와 멤버십 역할은 개별로 구성합니다.
        Group group = Group.create("회귀테스트", "회귀테스트", "", actor, NOW);
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        GroupMembership actorMembership = "PRIMARY".equals(actorRole)
                ? GroupMembership.primary(group, actor, NOW)
                : GroupMembership.member(group, actor, NOW);
        GroupMembership targetMembership = GroupMembership.member(group, target, NOW);
        if ("DEPUTY".equals(targetRole)) {
            targetMembership.changeDeputyRole("DEPUTY", NOW);
        }
        ReflectionTestUtils.setField(actorMembership, "id", ACTOR_MEMBERSHIP_ID);
        ReflectionTestUtils.setField(targetMembership, "id", TARGET_MEMBERSHIP_ID);

        // 서비스가 실제로 호출하는 사전 조회·잠금 요청에만 응답하도록 설정합니다.
        when(membershipRepository.findActiveGroupIdByUserId(ACTOR_ID)).thenReturn(Optional.of(GROUP_ID));
        when(userRepository.findByIdForUpdate(ACTOR_ID)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdForUpdate(TARGET_ID)).thenReturn(Optional.of(target));
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        when(membershipRepository.findActiveMembershipId(GROUP_ID, ACTOR_ID))
                .thenReturn(Optional.of(ACTOR_MEMBERSHIP_ID));
        when(membershipRepository.findActiveMembershipId(GROUP_ID, TARGET_ID))
                .thenReturn(Optional.of(TARGET_MEMBERSHIP_ID));
        when(membershipRepository.findByIdForUpdate(ACTOR_MEMBERSHIP_ID))
                .thenReturn(Optional.of(actorMembership));
        when(membershipRepository.findByIdForUpdate(TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(targetMembership));

        return new Fixture(group, actor, target, actorMembership, targetMembership);
    }

    /**
     * 역할 변경 응답을 위한 실제 사용자 프로필을 구성합니다.
     *
     * 프로필이 없는 경우의 예외와 구별하기 위해 정상 프로필을 사용합니다.
     * PRIMARY 이전 로직은 이 응답을 조회하지 않으므로 해당 테스트에서는 호출하지 않습니다.
     *
     * @param fixture 프로필 소유 회원을 포함한 테스트 데이터
     */
    private void arrangeTargetProfile(Fixture fixture) {
        // 역할 변경 응답에서는 target 사용자의 프로필만 조회합니다.
        UserProfile profile = UserProfile.create(fixture.target(), "대상회원", "대상회원", NOW);
        when(userProfileRepository.findByUserId(TARGET_ID)).thenReturn(Optional.of(profile));
    }

    /** 테스트에 필요한 실제 도메인 엔티티를 일관된 순서로 보관합니다. */
    private record Fixture(
            Group group,
            User actor,
            User target,
            GroupMembership actorMembership,
            GroupMembership targetMembership
    ) {}
}
