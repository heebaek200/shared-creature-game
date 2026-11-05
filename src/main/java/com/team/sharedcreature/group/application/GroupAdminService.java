package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.api.MyGroupResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupAdminChange;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupAdminChangeRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 그룹의 부 관리자 권한 변경, 주 관리자 이전을 처리
 *  - 관리자 역할 변경 시: 관련 사용자, 그룹, 멤버십을 잠금
 *  - 현재 권한을 재검증하고 변경 이력을 기록함
 */
@Slf4j
@Service
public class GroupAdminService {
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupAdminChangeRepository adminChangeRepository;

    private final GroupQueryService groupQueryService;

    private final EntityManager entityManager;
    private final Clock clock;

    public GroupAdminService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            GroupAdminChangeRepository adminChangeRepository,
            GroupQueryService groupQueryService,
            EntityManager entityManager,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.adminChangeRepository = adminChangeRepository;
        this.groupQueryService = groupQueryService;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * 관리자 명령에서 잠금과 권한 점증을 마친 엔티티 묶음 구조체
     *  - 같은 트랜잭션 내에서만 사용해야 함
     */
    private record LockedRoles(
            Group group,
            User actor,
            User target,
            GroupMembership actorMembership,
            GroupMembership targetMembership
    ) {}

    /**
     * 관리자 역할 변경에 필요한 사용자와 멤버십의 잠금을 획득
     *  - 사용자 ID ASC, 그룹, 멤버십 ID ASC로 잠금을 획득함. 이후 활성 소속과 주 관리자(PRIMARY) 권한을 다시 검사함.
     *  - 사전 조회 결과만으로 권한을 승인하지 않음.
     */
    private LockedRoles lockRoles(Long actorId, Long targetId) {
        if (actorId.equals(targetId)) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }

        // 그룹 ID만 사전 조회
        Long groupId = membershipRepository.findActiveGroupIdByUserId(actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));

        // 잠금1 : 두 사용자 행 (사용자 ID ASC)
        Map<Long, User> users = new HashMap<>();
        for (Long userId : Stream.of(actorId, targetId).sorted().toList()) {
            User user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() ->
                            new BusinessException(userId.equals(actorId) ? ErrorCode.ROLE_FORBIDDEN : ErrorCode.RESOURCE_NOT_FOUND));

            users.put(userId, user);
        }

        // 잠금2 : 그룹 잠금
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!"ACTIVE".equals(group.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // 활성 멤버십 ID 다시 조회
        Long actorMembershipId = membershipRepository
                .findActiveMembershipId(groupId, actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));

        Long targetMembershipId = membershipRepository
                .findActiveMembershipId(groupId, targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // 잠금3 : 멤버십 잠금 (멤버십 ID ASC)
        Map<Long, GroupMembership> memberships = new HashMap<>();
        for (Long membershipId : Stream.of(actorMembershipId, targetMembershipId).sorted().toList()) {
            GroupMembership membership = membershipRepository
                    .findByIdForUpdate(membershipId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));

            memberships.put(membershipId, membership);
        }

        GroupMembership actorMembership = memberships.get(actorMembershipId);
        GroupMembership targetMembership = memberships.get(targetMembershipId);
        User actor = users.get(actorId);
        User target = users.get(targetId);

        // 잠근 후 실제 사용자, 그룹, 상태를 검증
        validateMembership(actorMembership, actorId, groupId, ErrorCode.ROLE_FORBIDDEN);
        validateMembership(targetMembership, targetId, groupId, ErrorCode.RESOURCE_NOT_FOUND);
        // 현재 DB(세션이 아닌)의 주 관리자 기준으로 검사함
        if (
                !"ACTIVE".equals(actor.getStatus()) ||
                !"PRIMARY".equals(actorMembership.getRole()) ||
                group.getPrimaryAdminUser() == null ||
                !actorId.equals(group.getPrimaryAdminUser().getId()))
        {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        if (!"ACTIVE".equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        return new LockedRoles(group, actor, target, actorMembership, targetMembership);
    }

    /**
     * 잠근 멤버십이 예상한 그룹-사용자에 속하며 활성 상태인지 검사
     *  - 불일치하면 지정된 업무 오류 코드로 처리
     */
    private void validateMembership(
            GroupMembership membership,
            Long userId,
            Long groupId,
            ErrorCode errorCode
    ) {
        if (!"ACTIVE".equals(membership.getStatus()) ||
        !userId.equals(membership.getUser().getId()) ||
        !groupId.equals(membership.getGroup().getId())) {
            throw new BusinessException(errorCode);
        }
    }

    /**
     * 역할 변경 결과를 구성원 응답 DTO로 변환함
     */
    private GroupMemberResponse memberResponse(User target, GroupMembership membership) {
        String nickname = userProfileRepository.findByUserId(target.getId())
                .orElseThrow(() -> new IllegalStateException("대상 회원의 프로필이 없습니다: " + target.getId()))
                .getNickname();

        return new GroupMemberResponse(
                target.getId(),
                nickname,
                membership.getId(),
                membership.getRole(),
                membership.getStatus(),
                membership.getJoinedAt(),
                target.getLastActiveAt()
        );
    }

    /**
     * 지정한 회원에게 부 관리자(DEPUTY) 권한을 부여 또는 회수함
     *  - 요청자의 PRIMARY 권한과 대상의 활성 소속을 DB 잠금하고 검증함
     *  - 동일한 역할로의 요청은 변경 이력을 생성하지 않음
     */
    @Transactional
    public GroupMemberResponse changeMemberRole(
            Long actorId,
            Long targetId,
            String nextRole
    ) {
        // 변경 허용된 값
        if (!"MEMBER".equals(nextRole) && !"DEPUTY".equals(nextRole)) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }

        LockedRoles locked = lockRoles(actorId, targetId);
        GroupMembership targetMembership = locked.targetMembership();
        String previousRole = targetMembership.getRole();

        // 주 관리자(PRIMARY) 역할 변경은 해당 로직에서 처리하지 않음
        if (!"MEMBER".equals(previousRole) && !"DEPUTY".equals(previousRole)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        // 이미 해당 역할이면 변경하지 않음
        if (!previousRole.equals(nextRole)) {
            Instant now = Instant.now(clock);
            targetMembership.changeDeputyRole(nextRole, now);

            // 역할 변경 이력 기록
            adminChangeRepository.save(GroupAdminChange.deputyChanged(
                    locked.group(),
                    locked.actor(),
                    locked.target(),
                    previousRole,
                    nextRole,
                    now
            ));
        }

        return memberResponse(locked.target(), targetMembership);
    }

    /**
     * 현재 주 관리자 권한을 다른 활성 그룹 회원에게 이전
     *  - 기존 주 관리자(PRIMARY)를 회원(MEMBER)로 변경하고 먼저 Flush.
     *  - 이후 신규 주 관리자와 그룹의 관리자 참조를 변경하며, 모든 변경을 한 번에 Commit.
     */
    @Transactional
    public MyGroupResponse transferPrimaryAdmin(Long actorId, Long targetId) {
        LockedRoles locked = lockRoles(actorId, targetId);
        GroupMembership targetMembership = locked.targetMembership();
        String previousRole = targetMembership.getRole();
        Instant now = Instant.now(clock);

        // 새 관리자는 기존 활성 회원 또는 부 관리자이어야 함
        if (!"MEMBER".equals(previousRole) && !"DEPUTY".equals(previousRole)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        // 기존 주 관리자 UNIQUE 값을 먼저 해제함.
        locked.actorMembership.demotePrimary(now);
        entityManager.flush();

        // 새 주 관리자를 지정하고 그룹의 관리자 참조를 변경
        targetMembership.promotePrimary(now);
        locked.group().changePrimaryAdmin(locked.target(), now);

        // 이력 기록
        adminChangeRepository.save(GroupAdminChange.primaryTransferred(
                locked.group(),
                locked.actor(),
                locked.target(),
                previousRole,
                now
        ));

        // 변경된 멤버십과 그룹 정보를 먼저 반영
        entityManager.flush();

        return groupQueryService.getMyGroup(actorId);
    }
}
