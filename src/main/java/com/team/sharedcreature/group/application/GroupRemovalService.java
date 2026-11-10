package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.LeaveMyGroupResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupJoinRestriction;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.ModerationAction;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.group.infrastructure.ModerationActionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 그룹 관리자의 비활동 회원 강퇴 명령을 처리하는 Service
 *  - 역할과 활동 시각은 DB 행 잠금 이후의 최신 상태로 판정함
 *  - 사용자, 그룹, 멤버십 잠금 순서는 관리자 역할 변경 로직과 동일하게 유지해야 함
 */
@Slf4j
@Service
public class GroupRemovalService {

    private static final Duration INACTIVITY_THRESHOLD = Duration.ofHours(72).plusMinutes(10);
    private static final Duration JOIN_COOLDOWN = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupJoinRestrictionRepository restrictionRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final Clock clock;

    public GroupRemovalService(
            UserRepository userRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            GroupJoinRestrictionRepository restrictionRepository,
            ModerationActionRepository moderationActionRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionRepository = restrictionRepository;
        this.moderationActionRepository = moderationActionRepository;
        this.clock = clock;
    }

    /**
     * 현재 그룹의 비활동 구성원을 관리자 권한으로 강퇴한다
     *  - 사용자 ID ASC -> 그룹 -> 멤버십 ID ASC -> 가입 제한 순서로 잠금
     *  - 관리자 및 대상의 최신 역할, 계정 상태, 비활동 시간을 잠금 후 검증한다
     *  - 강퇴가 확정되면 멤버십 종료, 인원 감소, 24시간 제한, 감사 이력을 반영한다
     */
    @Transactional
    public LeaveMyGroupResponse removeInactiveMember(
            Long actorId,
            Long targetId,
            String reason
    ) {
        // 1. 관리자 자신은 대상할 수 없다
        if (actorId.equals(targetId)) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        // 2. 잠금 전에는 요청자의 그룹 ID만 조회한다.
        Long groupId = membershipRepository.findActiveGroupIdByUserId(actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));

        // 3. 두 사용자 행을 ID 오름차순으로 잠근다.
        Map<Long, User> users = new HashMap<>();

        for (Long userId : Stream.of(actorId, targetId).sorted().toList()) {
            User user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(
                            userId.equals(actorId) ? ErrorCode.ROLE_FORBIDDEN : ErrorCode.RESOURCE_NOT_FOUND
                    ));

            users.put(userId, user);
        }

        User actor = users.get(actorId);
        User target = users.get(targetId);

        // 4. 그룹 행을 잠그고 상태 재검사
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!"ACTIVE".equals(group.getStatus())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        // 5. 두 멤버의 ID를 다시 조회
        Long actorMembershipId = membershipRepository
                .findActiveMembershipId(groupId, actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));

        Long targetMembershipId = membershipRepository
                .findActiveMembershipId(groupId, targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // 6. 멤버십 행도 ID ASC로 잠금
        Map<Long, GroupMembership> memberships = new HashMap<>();

        for (Long membershipId : Stream.of(
                actorMembershipId,
                targetMembershipId
        ).sorted().toList()) {
            GroupMembership membership = membershipRepository
                    .findByIdForUpdate(membershipId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONCURRENT_MODIFICATION
                    ));

            memberships.put(membershipId, membership);
        }

        GroupMembership actorMembership = memberships.get(actorMembershipId);
        GroupMembership targetMembership = memberships.get(targetMembershipId);

        // 7. 잠금 이후 귀속과 활성 상태를 검증
        validateMembership(actorMembership, actorId, groupId, ErrorCode.ROLE_FORBIDDEN);
        validateMembership(targetMembership, targetId, groupId, ErrorCode.RESOURCE_NOT_FOUND);

        // 8. 실행자와 대상 계정이 활성 상태인자 확인
        if (!"ACTIVE".equals(actor.getStatus())) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        if (!"ACTIVE".equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // 9. 관리자 권한 검증 - 최신 DB 기준
        validateRemovalPermission(
                group,
                actorId,
                actorMembership.getRole(),
                targetMembership.getRole()
        );

        // 반정규화 그룹 카운터와 활성 멤버 사이의 모순 검증
        if (group.getCurrentMemberCount() <= 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Instant now = Instant.now(clock);
        Instant lastActiveAt = target.getLastActiveAt();

        // 10. 72시간 10분 미접속 조건을 최종 검사
        if (lastActiveAt == null
                || now.isBefore(lastActiveAt.plus(INACTIVITY_THRESHOLD))) {
            throw new BusinessException(ErrorCode.INACTIVITY_REQUIREMENT_NOT_MET);
        }

        // 11. 대상 멤버를 REMOVED 상태로 종료
        targetMembership.removeForInactivity(actor, now);
        group.decrementMemberCount(now);

        // 12. 사용자별 24시간 가입 제한 기록 생성 또는 갱신
        GroupJoinRestriction restriction = restrictionRepository
                .findByUserIdForUpdate(targetId)
                .orElse(null);

        if (restriction == null) {
            restrictionRepository.save(
                    GroupJoinRestriction.forDeparture(
                            target,
                            targetMembership,
                            "INACTIVE",
                            now
                    )
            );
        } else {
            restriction.renew(targetMembership, "INACTIVE", now);
        }

        // 13 관리자 강퇴 이력을 기록
        moderationActionRepository.save(
                ModerationAction.inactiveRemoval(
                        group,
                        targetMembership,
                        target,
                        actor,
                        reason,
                        now
                )
        );

        return new LeaveMyGroupResponse(
                groupId,
                targetId,
                now,
                "INACTIVE",
                now.plus(JOIN_COOLDOWN),
                false
        );
    }

    /**
     * 잠금 이후 멤버십의 사용자, 그룹, 상태를 검증
     *  - 사전 조회한 멤버십 정보만으로 명령을 승인하지 않는다.
     *  - 다른 그룹으로 이동했거나 종료된 멤버십은 거부한다.
     *  - 호출 측에서 실행자와 대상의 실패를 오류 코드로 구분해야 한다.
     */
    private void validateMembership(
            GroupMembership membership,
            Long userId,
            Long groupId,
            ErrorCode errorCode
    ) {
        if (!"ACTIVE".equals(membership.getStatus())
                || !userId.equals(membership.getUser().getId())
                || !groupId.equals(membership.getGroup().getId())) {
            throw new BusinessException(errorCode);
        }
    }

    /**
     * 요청자와 대상의 현재 역할 기준으로 강퇴 권한을 검증한다.
     */
    private void validateRemovalPermission(
            Group group,
            Long actorId,
            String actorRole,
            String targetRole
    ) {
        // 주 또는 부 관리자 역할을 갖고 있지 않다면 거부
        if (!"PRIMARY".equals(actorRole) && !"DEPUTY".equals(actorRole)) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        // 현재 주 관리자 참조도 실제 실행자와 일치해야 함
        if ("PRIMARY".equals(actorRole)
                && (group.getPrimaryAdminUser() == null
                || !actorId.equals(group.getPrimaryAdminUser().getId()))) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        // 부 관리자 명령으로 주 관리자(PRIMARY)를 강퇴할 수 없음
        if ("PRIMARY".equals(targetRole)) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        // 부 관리자(DEPUTY)가 다른 부 관라자(DEPUTY)를 강퇴할 수 없음
        if ("DEPUTY".equals(actorRole) && !"MEMBER".equals(targetRole)) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        // 알 수 없는 역할이나 허용되지 않은 상태를 거부함
        if (!"MEMBER".equals(targetRole) && !"DEPUTY".equals(targetRole)) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }
    }

}
