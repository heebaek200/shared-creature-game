
package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.api.AdminGroupSearchResponse;
import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupStatus;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 전체 관리자의 그룹 검색과 그룹별 활성 회원 조회를 담당합니다.
 *
 * 기존 그룹·멤버십 Repository를 사용해 읽기 전용 데이터를 반환합니다.
 * 관리자 권한 검사는 기존 Spring Security의 /api/v1/admin/** 정책을 따릅니다.
 */
@Service
@Transactional(readOnly = true)
public class AdminGroupQueryService {

    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;

    /**
     * 관리자 조회에 필요한 Repository를 주입받습니다.
     *
     * 그룹 검색과 활성 멤버십 조회만 수행합니다.
     * 그룹 상태나 멤버십을 변경하지 않습니다.
     *
     * @param groupRepository 그룹 Repository
     * @param membershipRepository 멤버십 Repository
     */
    public AdminGroupQueryService(
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository
    ) {
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * 전체 활성 그룹을 이름으로 검색하여 페이지 단위로 반환합니다.
     *
     * 일반 그룹 검색과 달리 공개 여부에 따른 제한을 적용하지 않습니다.
     * 검색어는 그룹 생성 시 사용한 정규화 규칙과 일치시킵니다.
     *
     * @param query 그룹 이름 검색어
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 결과 수
     * @return 관리자 그룹 검색 결과 페이지
     */
    public Page<AdminGroupSearchResponse> searchGroups(
            String query,
            int page,
            int size
    ) {
        // 그룹 생성 시 사용한 NFKC·소문자 정규화 규칙을 적용합니다.
        String normalizedQuery = normalizeQuery(query);

        Page<Group> groups = groupRepository.findAdminActiveGroups(
                normalizedQuery,
                PageRequest.of(page, size)
        );

        // 기존 엔티티를 관리자 선택용 DTO로 변환합니다.
        return groups.map(group -> new AdminGroupSearchResponse(
                group.getId(),
                group.getName(),
                group.getVisibility().name(),
                group.getCurrentMemberCount(),
                group.getCapacity()
        ));
    }

    /**
     * 지정한 활성 그룹의 현재 활성 멤버십 목록을 조회합니다.
     *
     * 그룹이 없거나 비활성 상태면 RESOURCE_NOT_FOUND를 반환합니다.
     * 기존 구성원 조회 쿼리를 재사용해 닉네임·역할·사용자 ID를 제공합니다.
     *
     * @param groupId 조회할 그룹 PK
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 결과 수
     * @return 그룹 활성 구성원 페이지
     */
    public Page<GroupMemberResponse> listMembers(
            Long groupId,
            int page,
            int size
    ) {
        // 종료된 그룹은 주 관리자 재지정 대상에서 제외합니다.
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                ));

        if (group.getStatus() != GroupStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // 기존 Repository의 JOIN DTO 조회를 그대로 사용합니다.
        return membershipRepository.findActiveMemberResponsesByGroupId(
                groupId,
                PageRequest.of(page, size)
        );
    }

    /**
     * 그룹 이름 검색어를 생성 시점과 동일한 규칙으로 정규화합니다.
     *
     * 앞뒤 공백 제거, Unicode NFKC 정규화, 소문자 변환을 수행합니다.
     * %, _, 역슬래시를 이스케이프해 LIKE에서 문자 그대로 검색합니다.
     *
     * @param query 원본 검색어
     * @return 정규화·이스케이프된 검색어
     */
    private String normalizeQuery(String query) {
        String value = query == null ? "" : query.strip();

        // 그룹 생성 로직의 정규화 규칙을 재사용합니다.
        String normalized = Normalizer.normalize(
                value,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);

        // SQL LIKE 패턴 문자를 일반 문자로 취급합니다.
        return normalized
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
