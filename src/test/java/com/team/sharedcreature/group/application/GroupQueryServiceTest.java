package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupQueryServiceTest {

    @Mock
    GroupMembershipRepository membershipRepository;

    @Mock
    UserProfileRepository profileRepository;

    private GroupQueryService service;

    @BeforeEach
    void setUp() {
        // 실제 DB를 사용하지 않고 조회 Service만 검증합니다.
        service = new GroupQueryService(
                membershipRepository,
                profileRepository
        );
    }

    /**
     * 현재 그룹을 조회하면 관리자와 멤버십 정보가 반환되는지 확인합니다.
     *
     * 활성 멤버십과 그룹을 준비한 뒤 주 관리자 프로필을 조회합니다.
     * 그룹 요약과 내 역할 및 관리자 닉네임이 올바른지 검증합니다.
     */
    @Test
    void 현재_그룹_조회는_주관리자와_내_멤버십을_반환한다() {
        GroupMembership membership = activeMembership(1L, 10L);
        Group group = membership.getGroup();

        // 그룹과 멤버십의 조회 대상 데이터를 준비합니다.
        when(group.getName()).thenReturn("새싹모임");
        when(group.getDescription()).thenReturn("함께 키워요");
        when(group.getCurrentMemberCount()).thenReturn(3);
        when(group.getCapacity()).thenReturn(20);
        when(membership.getId()).thenReturn(100L);
        when(membership.getRole()).thenReturn("DEPUTY");
        when(membership.getStatus()).thenReturn("ACTIVE");

        Instant joinedAt = Instant.parse("2026-11-03T00:00:00Z");
        when(membership.getJoinedAt()).thenReturn(joinedAt);

        // 주 관리자의 프로필을 준비합니다.
        User primaryAdmin = mock(User.class);
        UserProfile profile = mock(UserProfile.class);

        when(group.getPrimaryAdminUser()).thenReturn(primaryAdmin);
        when(primaryAdmin.getId()).thenReturn(2L);
        when(profileRepository.findByUserId(2L))
                .thenReturn(Optional.of(profile));
        when(profile.getNickname()).thenReturn("그룹장");

        var result = service.getMyGroup(1L);

        // 그룹 상세와 관리자 및 본인 역할을 검증합니다.
        assertThat(result.group().name()).isEqualTo("새싹모임");
        assertThat(result.group().remainingCapacity()).isEqualTo(17);
        assertThat(result.group().joinable()).isFalse();
        assertThat(result.group().joinBlockedReason())
                .isEqualTo("ALREADY_IN_GROUP");

        assertThat(result.primaryAdmin().nickname()).isEqualTo("그룹장");
        assertThat(result.myMembership().role()).isEqualTo("DEPUTY");
        assertThat(result.myMembership().joinedAt()).isEqualTo(joinedAt);
    }

    /**
     * 현재 활성 그룹이 없으면 그룹 상세 조회를 거부하는지 확인합니다.
     *
     * 사용자의 활성 멤버십이 존재하지 않는 상태를 모의합니다.
     * 그룹 정보가 없을 때 RESOURCE_NOT_FOUND 오류가 발생해야 합니다.
     */
    @Test
    void 그룹_미가입_사용자는_현재_그룹을_조회할_수_없다() {
        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.empty());

        // 활성 소속이 없는 사용자의 조회를 실행합니다.
        assertThatThrownBy(() -> service.getMyGroup(1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESOURCE_NOT_FOUND")
                );
    }

    /**
     * 구성원 목록은 본인 그룹의 데이터만 페이지 조회하는지 확인합니다.
     *
     * 활성 소속에서 얻은 그룹 ID를 Repository 조회 조건으로 사용합니다.
     * 요청한 페이지 번호와 크기가 그대로 전달되는지 검증합니다.
     */
    @Test
    void 구성원_목록은_현재_그룹_ID로_페이지_조회한다() {
        activeMembership(1L, 10L);

        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        GroupMemberResponse member = new GroupMemberResponse(
                2L, "일반회원", 102L, "MEMBER", "ACTIVE", now, now
        );

        var pageable = PageRequest.of(0, 20);

        // 그룹 10의 활성 구성원 조회 결과를 준비합니다.
        when(membershipRepository.findActiveMemberResponsesByGroupId(
                10L, pageable
        )).thenReturn(new PageImpl<>(List.of(member), pageable, 1));

        var result = service.listMyGroupMembers(1L, 0, 20);

        // 요청한 그룹 ID와 페이지 조건을 확인합니다.
        assertThat(result.getContent()).containsExactly(member);
        assertThat(result.getTotalElements()).isEqualTo(1);

        verify(membershipRepository)
                .findActiveMemberResponsesByGroupId(10L, pageable);
    }

    /**
     * 활성 멤버십과 현재 그룹을 구성하는 테스트 공통 메서드입니다.
     *
     * 사용자의 활성 멤버십을 Repository에서 반환하도록 설정합니다.
     * 그룹은 ACTIVE 상태로 만들어 조회 Service의 공통 검증을 통과시킵니다.
     *
     * @param userId 요청 사용자 ID
     * @param groupId 현재 소속 그룹 ID
     * @return 준비된 멤버십 Mock 객체
     */
    private GroupMembership activeMembership(Long userId, Long groupId) {
        GroupMembership membership = mock(GroupMembership.class);
        Group group = mock(Group.class);

        // 멤버십과 그룹의 필수 상태를 설정합니다.
        when(membershipRepository.findActiveWithGroupByUserId(userId))
                .thenReturn(Optional.of(membership));
        when(membership.getGroup()).thenReturn(group);
        when(group.getId()).thenReturn(groupId);
        when(group.getStatus()).thenReturn("ACTIVE");

        return membership;
    }

    /**
     * 활성 멤버십이 남아 있어도 그룹이 비활성화되었다면 상세 조회를 거부합니다.
     *
     * DB의 그룹 상태를 기준으로 접근 여부를 확인해야 합니다.
     * 관리자 프로필 조회까지 진행하지 않는지도 검증합니다.
     */
    @Test
    void 비활성_그룹의_상세는_조회할_수_없다() {
        GroupMembership membership = mock(GroupMembership.class);
        Group group = mock(Group.class);

        // 활성 멤버십이지만 그룹 자체는 비활성 상태로 구성합니다.
        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.of(membership));
        when(membership.getGroup()).thenReturn(group);
        when(group.getStatus()).thenReturn("INACTIVE");

        // 비활성 그룹의 내부 데이터는 노출하지 않아야 합니다.
        assertThatThrownBy(() -> service.getMyGroup(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(profileRepository);
    }

    /**
     * 현재 그룹이 없는 사용자는 구성원 목록에도 접근하지 못합니다.
     *
     * 그룹 상세 조회와 동일한 멤버십 검사를 사용해야 합니다.
     * 멤버십이 없는 경우 구성원 조회 쿼리를 실행하지 않아야 합니다.
     */
    @Test
    void 그룹_미가입_사용자는_구성원_목록을_조회할_수_없다() {
        // 현재 가입된 그룹이 없는 상태를 모의합니다.
        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.empty());

        // 조회 접근이 거부되고 이후 구성원 쿼리를 실행하지 않는지 확인합니다.
        assertThatThrownBy(() -> service.listMyGroupMembers(1L, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));

        verify(membershipRepository, never())
                .findActiveMemberResponsesByGroupId(anyLong(), any());
    }

}