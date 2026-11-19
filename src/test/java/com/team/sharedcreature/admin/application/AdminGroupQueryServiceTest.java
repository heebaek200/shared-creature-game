
package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.admin.api.AdminGroupSearchResponse;
import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembershipStatus;
import com.team.sharedcreature.group.domain.GroupRole;
import com.team.sharedcreature.group.domain.GroupStatus;
import com.team.sharedcreature.group.domain.GroupVisibility;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 전체 관리자 그룹 조회 Service의 검색·페이징·상태 검증 테스트입니다.
 *
 * 실제 DB를 사용하지 않고 Repository를 Mock으로 대체합니다.
 * 기존 조회 계약을 올바르게 호출하는지 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class AdminGroupQueryServiceTest {

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private GroupMembershipRepository membershipRepository;

    private AdminGroupQueryService service;

    /**
     * 테스트마다 새로운 조회 Service를 구성합니다.
     *
     * Mockito Repository를 주입해 조회 조건을 검증합니다.
     * 테스트 사이에 데이터 상태가 공유되지 않도록 합니다.
     */
    @BeforeEach
    void setUp() {
        service = new AdminGroupQueryService(
                groupRepository,
                membershipRepository
        );
    }

    /**
     * 그룹 검색어 정규화와 DTO 변환을 검증합니다.
     *
     * 전각 문자·대소문자·LIKE 특수문자를 포함한 검색어를 사용합니다.
     * 비공개 활성 그룹도 관리자 응답 DTO로 변환되는지 확인합니다.
     */
    @Test
    void 관리자_그룹_검색은_정규화된_조건과_페이지를_사용한다() {
        Group group = mock(Group.class);
        PageRequest pageable = PageRequest.of(0, 20);

        // 비공개 그룹을 검색 결과에 포함시켜 DTO 변환을 확인합니다.
        when(group.getId()).thenReturn(10L);
        when(group.getName()).thenReturn("Test_%");
        when(group.getVisibility()).thenReturn(GroupVisibility.PUBLIC);
        when(group.getCurrentMemberCount()).thenReturn(3);
        when(group.getCapacity()).thenReturn(20);

        // NFKC 변환 후 LIKE 특수문자가 이스케이프되어야 합니다.
        when(groupRepository.findAdminActiveGroups(
                "test\\_\\%",
                pageable
        )).thenReturn(
                new PageImpl<>(List.of(group), pageable, 1)
        );

        Page<AdminGroupSearchResponse> result = service.searchGroups("  ＴｅＳｔ_％  ", 0, 20);

        // 관리자 선택 UI에 필요한 필드만 반환합니다.
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(AdminGroupSearchResponse::groupId)
                .containsExactly(10L);
        assertThat(result.getContent().get(0).visibility())
                .isEqualTo("PUBLIC");

        verify(groupRepository).findAdminActiveGroups(
                "test\\_\\%",
                pageable
        );
    }

    /**
     * 활성 그룹의 회원 조회가 기존 Repository를 사용하는지 검증합니다.
     *
     * 활성 그룹을 준비하고 구성원 DTO 페이지를 반환하도록 설정합니다.
     * 요청한 그룹 ID와 페이지 크기가 유지되는지 확인합니다.
     */
    @Test
    void 활성_그룹의_구성원을_페이지_조회한다() {
        Group group = mock(Group.class);
        PageRequest pageable = PageRequest.of(0, 20);
        Instant joinedAt = Instant.parse("2026-11-01T00:00:00Z");

        GroupMemberResponse member = new GroupMemberResponse(
                21L,
                "테스트회원",
                100L,
                GroupRole.MEMBER,
                GroupMembershipStatus.ACTIVE,
                joinedAt,
                joinedAt
        );

        // 존재하는 활성 그룹만 회원 목록 조회를 허용합니다.
        when(groupRepository.findById(10L))
                .thenReturn(Optional.of(group));
        when(group.getStatus()).thenReturn(GroupStatus.ACTIVE);

        when(membershipRepository.findActiveMemberResponsesByGroupId(
                10L,
                pageable
        )).thenReturn(
                new PageImpl<>(List.of(member), pageable, 1)
        );

        Page<GroupMemberResponse> result = service.listMembers(10L, 0, 20);

        assertThat(result.getContent()).containsExactly(member);
        assertThat(result.getTotalElements()).isEqualTo(1);

        verify(membershipRepository)
                .findActiveMemberResponsesByGroupId(10L, pageable);
    }

    /**
     * 비활성 그룹의 회원 조회가 거부되는지 검증합니다.
     *
     * 그룹이 존재하더라도 상태가 ACTIVE가 아니면 조회할 수 없습니다.
     * 실패 시 멤버십 Repository에는 접근하지 않아야 합니다.
     */
    @Test
    void 비활성_그룹의_회원_목록은_조회할_수_없다() {
        Group group = mock(Group.class);

        when(groupRepository.findById(10L))
                .thenReturn(Optional.of(group));
        when(group.getStatus()).thenReturn(GroupStatus.INACTIVE);

        // 그룹 상태를 먼저 검사하고 멤버십 조회를 중단해야 합니다.
        assertThatThrownBy(() -> service.listMembers(10L, 0, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESOURCE_NOT_FOUND")
                );

        verifyNoInteractions(membershipRepository);
    }
}
