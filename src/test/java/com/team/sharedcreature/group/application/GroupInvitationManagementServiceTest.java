
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
 * STEP 6 초대 링크 조회와 재발급의 단위 테스트입니다.
 *
 * 실제 도메인 Entity의 상태 변경을 검증합니다.
 * Repository와 EntityManager는 Mockito로 대체합니다.
 * 시간과 권한을 고정하여 회귀 테스트를 안정적으로 실행합니다.
 */
@ExtendWith(MockitoExtension.class)
class GroupInvitationManagementServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-11-05T06:00:00Z");

    @Mock UserRepository userRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMembershipRepository membershipRepository;
    @Mock GroupInviteLinkRepository inviteLinkRepository;
    @Mock EntityManager entityManager;

    private GroupInvitationManagementService service;

    /**
     * 초대 링크 관리 Service를 테스트 환경으로 구성합니다.
     *
     * 시각과 앱 기본 URL을 고정합니다.
     * 각 Repository는 실제 DB에 접근하지 않도록 대체합니다.
     * 개별 테스트에서 필요한 조회 응답만 준비합니다.
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
     * PRIMARY의 재발급이 기존 링크를 폐기하고 새 링크를 생성하는지 검증합니다.
     *
     * 기존 링크의 상태가 INVALIDATED로 변경되어야 합니다.
     * 신규 링크에는 SHA-256 해시만 저장되어야 합니다.
     * 이전 링크 UPDATE의 Flush가 새 링크 INSERT보다 먼저 실행되어야 합니다.
     */
    @Test
    void PRIMARY_재발급은_기존_링크를_폐기하고_새_링크를_저장한다() {
        Fixture f = fixture();

        // 실제 잠금 조회와 기존 초대 링크를 연결합니다.
        arrangeRotation(f);

        // 새 초대 링크를 발급합니다.
        var response = service.rotateInvitation(1L);

        // 기존 링크와 반환 URL이 올바른지 확인합니다.
        assertThat(f.previous().getStatus()).isEqualTo("INVALIDATED");
        assertThat(f.previous().getInvalidatedAt()).isEqualTo(NOW);
        assertThat(response.groupId()).isEqualTo(10L);
        assertThat(response.issuedByUserId()).isEqualTo(1L);
        assertThat(response.unlimitedUses()).isTrue();
        assertThat(response.expiresAt()).isNull();

        // 저장되는 토큰은 원문이 아닌 SHA-256 해시여야 합니다.
        ArgumentCaptor<GroupInviteLink> captor =
                ArgumentCaptor.forClass(GroupInviteLink.class);

        verify(inviteLinkRepository).save(captor.capture());

        String token = response.invitationUrl()
                .substring("http://localhost:5173/invite/".length());

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(captor.getValue().getTokenHash())
                .isEqualTo(Hashing.sha256(token));
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getIssuedByUser()).isSameAs(f.actor());

        // 기존 링크 UPDATE가 신규 INSERT보다 먼저 수행돼야 합니다.
        InOrder order = inOrder(entityManager, inviteLinkRepository);
        order.verify(entityManager).flush();
        order.verify(inviteLinkRepository).save(any(GroupInviteLink.class));
    }

    /**
     * PRIMARY가 아닌 회원의 관리 정보 조회를 차단합니다.
     *
     * 활성 멤버십이 있더라도 MEMBER에게 관리 권한은 없습니다.
     * 그룹 PRIMARY 참조와 멤버십 역할 모두 확인해야 합니다.
     * 권한 거부 시 초대 링크 저장소에 접근하지 않아야 합니다.
     */
    @Test
    void MEMBER는_초대_관리_정보를_조회할_수_없다() {
        Fixture f = fixture();

        // PRIMARY가 아닌 활성 멤버십으로 교체합니다.
        GroupMembership member =
                GroupMembership.member(f.group(), f.actor(), NOW);

        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.of(member));

        // 역할 부족은 업무 예외로 처리합니다.
        assertThatThrownBy(() -> service.getInvitationManagement(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("ROLE_FORBIDDEN"));

        verifyNoInteractions(inviteLinkRepository);
    }

    /**
     * 실제 Entity를 사용하여 PRIMARY 그룹과 초대 링크를 구성합니다.
     *
     * JPA를 사용하지 않으므로 PK는 Reflection으로 부여합니다.
     * 그룹과 멤버십에는 같은 PRIMARY를 연결합니다.
     * 모든 테스트가 독립적인 데이터를 사용하도록 생성합니다.
     */
    private Fixture fixture() {
        // 테스트 사용자와 그룹을 생성합니다.
        User actor = User.register("USER", NOW);
        ReflectionTestUtils.setField(actor, "id", 1L);

        Group group = Group.create("테스트", "테스트", "", actor, NOW);
        ReflectionTestUtils.setField(group, "id", 10L);

        // 초기 PRIMARY 멤버십을 생성합니다.
        GroupMembership membership =
                GroupMembership.primary(group, actor, NOW);
        ReflectionTestUtils.setField(membership, "id", 101L);

        // 기존 초대 링크도 실제 도메인 객체로 준비합니다.
        GroupInviteLink previous = GroupInviteLink.issue(
                group, Hashing.sha256("previous-token"), actor, NOW.minusSeconds(60)
        );

        return new Fixture(actor, group, membership, previous);
    }

    /**
     * 재발급 Service의 사전 조회와 잠금 응답을 준비합니다.
     *
     * 요청 사용자와 그룹, 초대 링크, 멤버십 ID가 일치하도록 설정합니다.
     * 실제 Service에서 사용하는 순서와 동일한 조회 메서드를 모의합니다.
     * 테스트는 이후 도메인 변경 및 Repository 호출 순서를 검사합니다.
     */
    private void arrangeRotation(Fixture f) {
        // 요청자 및 그룹 잠금 조회를 모의합니다.
        when(membershipRepository.findActiveGroupIdByUserId(1L))
                .thenReturn(Optional.of(10L));
        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(f.actor()));
        when(groupRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(f.group()));

        // 활성 초대 링크와 PRIMARY 멤버십 잠금을 모의합니다.
        when(inviteLinkRepository.findActiveByGroupIdForUpdate(10L))
                .thenReturn(Optional.of(f.previous()));
        when(membershipRepository.findActiveMembershipId(10L, 1L))
                .thenReturn(Optional.of(101L));
        when(membershipRepository.findByIdForUpdate(101L))
                .thenReturn(Optional.of(f.membership()));
    }

    private record Fixture(
            User actor,
            Group group,
            GroupMembership membership,
            GroupInviteLink previous
    ) {
    }
}
