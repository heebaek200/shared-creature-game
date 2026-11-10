package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.domain.AdminAuditLog;
import com.team.sharedcreature.admin.infrastructure.AdminAuditLogRepository;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.GroupSummaryResponse;
import com.team.sharedcreature.group.api.MyGroupResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupAdminChange;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupAdminChangeRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** 전체 관리자의 주 관리자 복구를 잠금과 감사 기록으로 원자 처리합니다. */
@Service
public class AdminGroupRecoveryCommandExecutor {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupAdminChangeRepository adminChangeRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    /** 주 관리자 복구에 필요한 저장소를 주입받습니다. */
    public AdminGroupRecoveryCommandExecutor(
            UserRepository userRepository,
            UserProfileRepository profileRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            GroupAdminChangeRepository adminChangeRepository,
            AdminAuditLogRepository auditLogRepository,
            EntityManager entityManager,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.adminChangeRepository = adminChangeRepository;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** 같은 그룹의 활성 회원에게 전체 관리자 권한으로 주 관리자 역할을 이전합니다. */
    @Transactional
    public MyGroupResponse transfer(Long actorId, Long groupId, Long targetId, String reason) {
        Group snapshot = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (snapshot.getPrimaryAdminUser() == null) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Long previousPrimaryId = snapshot.getPrimaryAdminUser().getId();
        if (previousPrimaryId.equals(targetId)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Map<Long, User> users = new HashMap<>();
        for (Long userId : Stream.of(actorId, previousPrimaryId, targetId).distinct().sorted().toList()) {
            users.put(userId, userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        }
        User actor = users.get(actorId);
        User previousPrimary = users.get(previousPrimaryId);
        User target = users.get(targetId);
        if (!"ACTIVE".equals(actor.getStatus()) || !"SUPER_ADMIN".equals(actor.getGlobalRole())) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"ACTIVE".equals(group.getStatus())
                || group.getPrimaryAdminUser() == null
                || !previousPrimaryId.equals(group.getPrimaryAdminUser().getId())
                || !"ACTIVE".equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Long previousMembershipId = membershipRepository.findActiveMembershipId(groupId, previousPrimaryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        Long targetMembershipId = membershipRepository.findActiveMembershipId(groupId, targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Map<Long, GroupMembership> memberships = new HashMap<>();
        for (Long membershipId : Stream.of(previousMembershipId, targetMembershipId).sorted().toList()) {
            memberships.put(membershipId, membershipRepository.findByIdForUpdate(membershipId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION)));
        }
        GroupMembership previousMembership = memberships.get(previousMembershipId);
        GroupMembership targetMembership = memberships.get(targetMembershipId);
        String targetPreviousRole = targetMembership.getRole();
        if (!"PRIMARY".equals(previousMembership.getRole())
                || (!"MEMBER".equals(targetPreviousRole) && !"DEPUTY".equals(targetPreviousRole))) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Instant now = clock.instant();
        previousMembership.demotePrimary(now);
        entityManager.flush();
        targetMembership.promotePrimary(now);
        group.changePrimaryAdmin(target, now);
        adminChangeRepository.save(GroupAdminChange.primaryTransferred(
                group, actor, target, targetPreviousRole, now
        ));
        auditLogRepository.save(AdminAuditLog.create(
                actor, "TRANSFER_PRIMARY_AS_SUPER_ADMIN", "GROUP", groupId, group,
                "SUCCEEDED", reason, "{}", now
        ));

        String nickname = profileRepository.findByUserId(targetId)
                .orElseThrow(() -> new IllegalStateException("새 주 관리자 프로필이 없습니다."))
                .getNickname();
        int remaining = Math.max(0, group.getCapacity() - group.getCurrentMemberCount());
        GroupMembership actorMembership = membershipRepository.findByUserIdAndStatus(actorId, "ACTIVE")
                .filter(membership -> groupId.equals(membership.getGroup().getId()))
                .orElse(null);
        boolean actorAlreadyJoined = actorMembership != null;
        boolean joinable = !actorAlreadyJoined && remaining > 0;
        String blockedReason = actorAlreadyJoined
                ? "ALREADY_IN_GROUP"
                : (remaining > 0 ? null : "GROUP_FULL");
        return new MyGroupResponse(
                new GroupSummaryResponse(
                        group.getId(), group.getName(), group.getDescription(), group.getCurrentMemberCount(),
                        group.getCapacity(), remaining, joinable, blockedReason
                ),
                new MyGroupResponse.PrimaryAdminResponse(targetId, nickname),
                actorMembership == null ? null : new MyGroupResponse.MyMembershipResponse(
                        actorMembership.getId(), groupId, actorMembership.getRole(),
                        actorMembership.getStatus(), actorMembership.getJoinedAt()
                )
        );
    }
}
