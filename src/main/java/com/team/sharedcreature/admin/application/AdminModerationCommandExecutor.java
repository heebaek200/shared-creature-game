package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.domain.AdminAuditLog;
import com.team.sharedcreature.admin.infrastructure.AdminAuditLogRepository;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupJoinRestriction;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.ModerationAction;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.group.infrastructure.ModerationActionRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** 신고 기각·강퇴의 잠금, 상태 전이와 성공 감사를 한 트랜잭션으로 처리합니다. */
@Service
public class AdminModerationCommandExecutor {

    private final UserReportRepository reportRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupJoinRestrictionRepository restrictionRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final Clock clock;

    /** 신고 처리에 필요한 모든 상태 저장소를 주입받습니다. */
    public AdminModerationCommandExecutor(
            UserReportRepository reportRepository,
            UserRepository userRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            GroupJoinRestrictionRepository restrictionRepository,
            ModerationActionRepository moderationActionRepository,
            AdminAuditLogRepository auditLogRepository,
            Clock clock
    ) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionRepository = restrictionRepository;
        this.moderationActionRepository = moderationActionRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /** 접수 중인 신고를 기각하고 성공 감사를 함께 저장합니다. */
    @Transactional
    public void dismiss(Long actorId, Long reportId, String reason) {
        UserReport report = requireReceivedReport(reportId);
        User actor = requireSuperAdmin(actorId);
        Instant now = clock.instant();
        report.reject(actor, reason, now);
        auditLogRepository.save(AdminAuditLog.create(
                actor, "DISMISS_REPORT", "USER_REPORT", reportId, report.getGroup(),
                "SUCCEEDED", reason, "{}", now
        ));
    }

    /** 접수 중인 신고의 현재 활성 회원을 강퇴하고 제한·감사를 함께 저장합니다. */
    @Transactional
    public void remove(Long actorId, Long reportId, String reason) {
        UserReport report = requireReceivedReport(reportId);
        Long targetId = report.getReportedUser().getId();
        Map<Long, User> users = new HashMap<>();
        for (Long userId : Stream.of(actorId, targetId).distinct().sorted().toList()) {
            users.put(userId, userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        }
        User actor = users.get(actorId);
        User target = users.get(targetId);
        validateSuperAdmin(actor);

        Long groupId = report.getGroup().getId();
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Long membershipId = membershipRepository.findActiveMembershipId(groupId, targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        GroupMembership membership = membershipRepository.findByIdForUpdate(membershipId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        if (!"ACTIVE".equals(group.getStatus())
                || !"ACTIVE".equals(target.getStatus())
                || !"ACTIVE".equals(membership.getStatus())
                || !groupId.equals(membership.getGroup().getId())
                || "PRIMARY".equals(membership.getRole())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Instant now = clock.instant();
        membership.removeForReport(actor, now);
        group.decrementMemberCount(now);
        GroupJoinRestriction restriction = restrictionRepository.findByUserIdForUpdate(targetId).orElse(null);
        if (restriction == null) {
            restrictionRepository.save(GroupJoinRestriction.forDeparture(target, membership, "REPORTED", now));
        } else {
            restriction.renew(membership, "REPORTED", now);
        }
        moderationActionRepository.save(ModerationAction.reportedRemoval(
                report, membership, target, actor, reason, now
        ));
        report.resolve(actor, reason, now);
        auditLogRepository.save(AdminAuditLog.create(
                actor, "REMOVE_REPORTED_MEMBER", "USER_REPORT", reportId, group,
                "SUCCEEDED", reason, "{}", now
        ));
    }

    private UserReport requireReceivedReport(Long reportId) {
        UserReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"RECEIVED".equals(report.getStatus())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        return report;
    }

    private User requireSuperAdmin(Long actorId) {
        User actor = userRepository.findByIdForUpdate(actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));
        validateSuperAdmin(actor);
        return actor;
    }

    private void validateSuperAdmin(User actor) {
        if (!"ACTIVE".equals(actor.getStatus()) || !"SUPER_ADMIN".equals(actor.getGlobalRole())) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }
    }
}
