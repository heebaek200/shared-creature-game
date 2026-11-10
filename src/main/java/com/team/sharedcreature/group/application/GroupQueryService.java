package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.api.GroupSummaryResponse;
import com.team.sharedcreature.group.api.MyGroupResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 사용자가 소속된 그룹의 조회 업무를 처리합니다.
 *
 * 최신 활성 멤버십을 기준으로 그룹 접근 범위를 결정합니다.
 * 조회 전용 트랜잭션에서 그룹 상세와 구성원 목록을 반환합니다.
 */
@Service
@Transactional(readOnly = true)
public class GroupQueryService {

    private final GroupMembershipRepository membershipRepository;
    private final UserProfileRepository profileRepository;

    /**
     * 그룹 조회에 필요한 Repository를 주입받습니다.
     *
     * 멤버십을 기준으로 사용자의 현재 그룹을 결정합니다.
     * 주 관리자 닉네임은 프로필 Repository에서 조회합니다.
     */
    public GroupQueryService(
            GroupMembershipRepository membershipRepository,
            UserProfileRepository profileRepository
    ) {
        this.membershipRepository = membershipRepository;
        this.profileRepository = profileRepository;
    }

    /**
     * 현재 사용자가 소속된 그룹의 상세 정보를 반환합니다.
     *
     * 활성 멤버십과 그룹 상태를 검증합니다.
     * 주 관리자 및 자신의 멤버십 정보를 계약에 맞게 구성합니다.
     *
     * @param userId 인증 사용자 ID
     * @return 현재 그룹 상세 DTO
     */
    public MyGroupResponse getMyGroup(Long userId) {
        // 세션 역할이 아니라 DB의 현재 활성 멤버십으로 판정합니다.
        GroupMembership membership = requireActiveMembership(userId);
        Group group = membership.getGroup();

        // 활성 그룹은 주 관리자 정보가 반드시 존재해야 합니다.
        User primaryAdmin = group.getPrimaryAdminUser();
        if (primaryAdmin == null) {
            throw new IllegalStateException(
                    "활성 그룹에 주 관리자가 없습니다: " + group.getId()
            );
        }

        UserProfile profile = profileRepository
                .findByUserId(primaryAdmin.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "주 관리자 프로필이 없습니다: " + primaryAdmin.getId()
                ));

        // 현재 그룹 구성원은 다른 그룹에 새로 가입할 수 없습니다.
        int remaining = Math.max(
                0,
                group.getCapacity() - group.getCurrentMemberCount()
        );

        GroupSummaryResponse summary = new GroupSummaryResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCurrentMemberCount(),
                group.getCapacity(),
                remaining,
                false,
                "ALREADY_IN_GROUP"
        );

        // 주 관리자 표시 정보와 내 멤버십을 구성합니다.
        var primary = new MyGroupResponse.PrimaryAdminResponse(
                primaryAdmin.getId(),
                profile.getNickname()
        );

        var myMembership = new MyGroupResponse.MyMembershipResponse(
                membership.getId(),
                group.getId(),
                membership.getRole(),
                membership.getStatus(),
                membership.getJoinedAt()
        );

        return new MyGroupResponse(summary, primary, myMembership);
    }

    /**
     * 현재 그룹에 소속된 활성 구성원 목록을 조회합니다.
     *
     * 먼저 요청 사용자의 현재 그룹을 DB에서 확인합니다.
     * 조회된 그룹 ID로 페이징하여 다른 그룹의 회원 정보 노출을 방지합니다.
     *
     * @param userId 인증 사용자 ID
     * @param page 조회 페이지 번호
     * @param size 페이지 크기
     * @return 활성 구성원 페이지
     */
    public Page<GroupMemberResponse> listMyGroupMembers(
            Long userId,
            int page,
            int size
    ) {
        // 본인 소속 그룹만 조회할 수 있도록 범위를 고정합니다.
        GroupMembership membership = requireActiveMembership(userId);

        // 쿼리 자체에서 활성 구성원만 반환합니다.
        return membershipRepository.findActiveMemberResponsesByGroupId(
                membership.getGroup().getId(),
                PageRequest.of(page, size)
        );
    }

    /**
     * 인증 사용자의 유효한 활성 그룹 멤버십을 반환합니다.
     *
     * 세션에 기록된 역할과 그룹 ID를 신뢰하지 않고 실제 DB를 조회합니다.
     * 소속이 없거나 그룹이 비활성화되었다면 조회를 중단합니다.
     *
     * @param userId 인증 사용자 ID
     * @return 검증된 활성 멤버십
     */
    private GroupMembership requireActiveMembership(Long userId) {
        GroupMembership membership = membershipRepository
                .findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                ));

        // 종료된 그룹의 구성원 조회는 허용하지 않습니다.
        if (!"ACTIVE".equals(membership.getGroup().getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        return membership;
    }

}