package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * STEP 6 초대 링크 관리의 실패 경로를 검증하는 독립 단위 테스트입니다.
 *
 * 기존 GroupInvitationManagementServiceTest는 변경하지 않습니다.
 * 외부 저장소와 영속성 컨텍스트를 Mockito로 대체합니다.
 * 권한 검증 실패 시 기존 링크를 변경하지 않는지 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class GroupInvitationManagementRegressionTest {

    private static final Instant NOW = Instant.parse("2026-11-05T06:00:00Z");
    private static final long USER_ID = 1L;
    private static final long GROUP_ID = 10L;
    private static final long MEMBERSHIP_ID = 101L;

    @Mock UserRepository userRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMembershipRepository membershipRepository;
    @Mock GroupInviteLinkRepository inviteLinkRepository;
    @Mock EntityManager entityManager;

    private GroupInvitationManagementService service;

    /**
     * 모든 테스트에 동일한 시각과 URL을 공급합니다.
     *
     * Service 내부의 임의 난수는 실제 생성기를 그대로 사용합니다.
     * 실패 경로에서는 난수를 사용하거나 DB를 변경하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        service = new GroupInvitationManagementService(
                userRepository,
                groupRepository,
                membershipRepository,
                inviteLinkRepository,
                entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "http://localhost:5173"
        );
    }

    /**
     * PRIMARY가 초대 관리 정보를 조회할 때 원문 URL 없이 메타데이터를 반환합니다.
     *
     * 기존 링크의 발급 일시와 발급자 ID가 보존되어야 합니다.
     * GET 요청은 변경 작업이 아니므로 저장과 Flush를 실행하면 안 됩니다.
     */
    @Test
    void PRIMARY_관리조회는_발급정보만_반환하고_쓰기작업을_하지_않는다() {
        Fixture fixture = fixture("PRIMARY");
        when(membershipRepository.findActiveWithGroupByUserId(USER_ID))
                .thenReturn(Optional.of(fixture.membership()));
        when(inviteLinkRepository.findByGroup_IdAndStatus(GROUP_ID, "ACTIVE"))
                .thenReturn(Optional.of(fixture.link()));

        // 정상적인 초대 관리 조회 결과만 검사합니다.
        var response = service.getInvitationManagement(USER_ID);

        assertThat(response.groupId()).isEqualTo(GROUP_ID);
        assertThat(response.issuedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(response.issuedByUserId()).isEqualTo(USER_ID);
        assertThat(response.unlimitedUses()).isTrue();
        assertThat(response.expiresAt()).isNull();
        verifyNoInteractions(entityManager);
        verify(inviteLinkRepository, never()).save(any());
    }

    /**
     * 조회 요청에서도 활성 링크가 없다면 무단으로 새 링크를 발행하지 않습니다.
     *
     * DB가 비정상적인 상태인 경우 정합성 예외를 반환해야 합니다.
     * 실패한 조회는 저장소에 변경을 남기지 않아야 합니다.
     */
    @Test
    void 활성_링크가_없으면_관리조회는_409를_반환한다() {
        Fixture fixture = fixture("PRIMARY");
        when(membershipRepository.findActiveWithGroupByUserId(USER_ID))
                .thenReturn(Optional.of(fixture.membership()));
        when(inviteLinkRepository.findByGroup_IdAndStatus(GROUP_ID, "ACTIVE"))
                .thenReturn(Optional.empty());

        // 조회는 명시적 재발급 명령을 대신하지 않습니다.
        assertCode("CONCURRENT_MODIFICATION", () -> service.getInvitationManagement(USER_ID));
        verify(inviteLinkRepository, never()).save(any());
        verifyNoInteractions(entityManager);
    }

    /**
     * ROLE이 MEMBER인 사용자는 초대 링크를 회전시킬 수 없습니다.
     *
     * 사용자가 그룹에 소속돼 있더라도 현재 PRIMARY 역할이 필요합니다.
     * 기존 링크가 폐기되거나 새 링크가 저장되지 않아야 합니다.
     */
    @Test
    void MEMBER의_재발급은_403이며_기존_링크를_보존한다() {
        Fixture fixture = fixture("MEMBER");
        stubRotationThroughMembership(fixture);

        // 기존 링크를 조회했더라도 권한을 잃은 사용자는 재발급 불가입니다.
        assertCode("ROLE_FORBIDDEN", () -> service.rotateInvitation(USER_ID));
        assertThat(fixture.link().getStatus()).isEqualTo("ACTIVE");
        assertThat(fixture.link().getInvalidatedAt()).isNull();
        verifyNoInteractions(entityManager);
        verify(inviteLinkRepository, never()).save(any());
    }

    /**
     * DEPUTY도 PRIMARY 전용 초대 재발급을 사용할 수 없습니다.
     *
     * 재발급 권한은 Spring Security뿐 아니라 Service에서도 검증합니다.
     * 기존 링크와 발급 기록에는 어떤 변경도 없어야 합니다.
     */
    @Test
    void DEPUTY의_재발급은_403이며_기존_링크를_보존한다() {
        Fixture fixture = fixture("DEPUTY");
        stubRotationThroughMembership(fixture);

        // 그룹 안의 관리자 역할이더라도 재발급 권한은 없습니다.
        assertCode("ROLE_FORBIDDEN", () -> service.rotateInvitation(USER_ID));
        assertThat(fixture.link().getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(entityManager);
        verify(inviteLinkRepository, never()).save(any());
    }

    /**
     * 종료된 그룹에서는 초대 링크 재발급을 수행하지 않습니다.
     *
     * 이미 종료된 그룹의 링크를 잠그거나 다시 생성하면 안 됩니다.
     * 그룹 상태가 ACTIVE인지 잠금 후 재검사해야 합니다.
     */
    @Test
    void INACTIVE_그룹은_재발급을_거부한다() {
        Fixture fixture = fixture("PRIMARY");
        ReflectionTestUtils.setField(fixture.group(), "status", "INACTIVE");
        stubRotationThroughGroup(fixture);

        // 링크 변경 없이 그룹 상태 검증에서 실패해야 합니다.
        assertCode("CONCURRENT_MODIFICATION", () -> service.rotateInvitation(USER_ID));
        verifyNoInteractions(inviteLinkRepository, entityManager);
    }

    /**
     * 활성 링크가 존재하지 않으면 재발급 명령을 거부합니다.
     *
     * 운영 정책상 링크가 반드시 존재하는 그룹만 회전할 수 있습니다.
     * 새 링크를 임의로 생성하지 않아 현재 코드의 계약을 유지합니다.
     */
    @Test
    void 활성_링크가_없으면_재발급은_409이며_저장하지_않는다() {
        Fixture fixture = fixture("PRIMARY");
        stubRotationThroughGroup(fixture);
        when(inviteLinkRepository.findActiveByGroupIdForUpdate(GROUP_ID))
                .thenReturn(Optional.empty());

        // 무효화할 링크가 없으면 새 링크도 생성하지 않습니다.
        assertCode("CONCURRENT_MODIFICATION", () -> service.rotateInvitation(USER_ID));
        verify(inviteLinkRepository, never()).save(any());
        verifyNoInteractions(entityManager);
    }

    /**
     * 사용자의 멤버십이 조회 직후 종료되면 재발급을 허용하지 않습니다.
     *
     * 사전 그룹 ID 조회만으로 권한을 확정하지 않습니다.
     * 활성 멤버십 ID의 재조회 결과가 없으면 즉시 거부해야 합니다.
     */
    @Test
    void 멤버십이_사라진_요청은_기존_링크를_변경하지_않는다() {
        Fixture fixture = fixture("PRIMARY");
        stubRotationThroughGroup(fixture);
        when(inviteLinkRepository.findActiveByGroupIdForUpdate(GROUP_ID))
                .thenReturn(Optional.of(fixture.link()));
        when(membershipRepository.findActiveMembershipId(GROUP_ID, USER_ID))
                .thenReturn(Optional.empty());

        // 초대 링크가 잠겨 있어도 권한 재검증 전에는 변경하지 않습니다.
        assertCode("ROLE_FORBIDDEN", () -> service.rotateInvitation(USER_ID));
        assertThat(fixture.link().getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(entityManager);
        verify(inviteLinkRepository, never()).save(any());
    }

    /**
     * 업무 예외의 코드가 기대한 값과 같은지 검사합니다.
     *
     * HTTP 상태는 별도 Controller/Security 테스트에서 확인합니다.
     * 여기서는 Service가 반환하는 업무 오류 코드만 검증합니다.
     */
    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    /**
     * 기존 도메인 팩터리로 사용자, 그룹, 멤버십, 초대 링크를 준비합니다.
     *
     * 멤버십 ROLE만 테스트 조건에 맞게 설정합니다.
     * MySQL 없이 엔티티 식별자를 부여하기 위해 Reflection을 사용합니다.
     */
    private Fixture fixture(String role) {
        User actor = User.register("USER", NOW);
        ReflectionTestUtils.setField(actor, "id", USER_ID);

        // 그룹과 기존 초대 링크는 동일한 PRIMARY 사용자로 시작합니다.
        Group group = Group.create("테스트그룹", "테스트그룹", "", actor, NOW);
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        GroupMembership membership = GroupMembership.primary(group, actor, NOW);
        ReflectionTestUtils.setField(membership, "id", MEMBERSHIP_ID);

        // 권한 거부 상황에서는 해당 멤버십의 역할만 바꿉니다.
        if (!"PRIMARY".equals(role)) {
            membership.demotePrimary(NOW);
            if ("DEPUTY".equals(role)) {
                membership.changeDeputyRole("DEPUTY", NOW);
            }
        }
        GroupInviteLink link = GroupInviteLink.issue(
                group, Hashing.sha256("old-token"), actor, NOW.minusSeconds(60)
        );
        return new Fixture(actor, group, membership, link);
    }

    /**
     * 그룹 조회 단계까지 Service Repository Mock을 연결합니다.
     *
     * 그룹 ID 사전 조회 후 사용자와 그룹에 순서대로 잠금이 걸립니다.
     * 테스트는 실제 Group 엔티티를 반환해 상태 검증을 수행합니다.
     */
    private void stubRotationThroughGroup(Fixture fixture) {
        when(membershipRepository.findActiveGroupIdByUserId(USER_ID))
                .thenReturn(Optional.of(GROUP_ID));
        when(userRepository.findByIdForUpdate(USER_ID))
                .thenReturn(Optional.of(fixture.actor()));
        when(groupRepository.findByIdForUpdate(GROUP_ID))
                .thenReturn(Optional.of(fixture.group()));
    }

    /**
     * 멤버십 잠금과 역할 검사 단계까지 Mock을 연결합니다.
     *
     * Service가 기존 링크를 조회한 다음 멤버십을 잠그는 흐름을 재현합니다.
     * 실패 시 발급·무효화 단계로 진입하지 않아야 합니다.
     */
    private void stubRotationThroughMembership(Fixture fixture) {
        stubRotationThroughGroup(fixture);
        when(inviteLinkRepository.findActiveByGroupIdForUpdate(GROUP_ID))
                .thenReturn(Optional.of(fixture.link()));
        when(membershipRepository.findActiveMembershipId(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(MEMBERSHIP_ID));
        when(membershipRepository.findByIdForUpdate(MEMBERSHIP_ID))
                .thenReturn(Optional.of(fixture.membership()));
    }

    private record Fixture(User actor, Group group, GroupMembership membership, GroupInviteLink link) {}
}
