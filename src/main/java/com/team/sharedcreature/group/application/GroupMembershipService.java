package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.realtime.GroupPresenceRevocationService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.*;
import com.team.sharedcreature.group.domain.*;
import com.team.sharedcreature.group.infrastructure.*;
import com.team.sharedcreature.season.application.SeasonContributionMembershipService;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * 그룹 생성·가입·탈퇴의 정합성 경계를 소유합니다.
 */
@Service
public class GroupMembershipService {

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupJoinRestrictionRepository restrictionRepository;
    private final GroupInviteLinkRepository inviteLinkRepository;
    private final GroupAdminChangeRepository adminChangeRepository;
    private final GroupSeasonRepository groupSeasonRepository;
    private final GroupCreatureRepository groupCreatureRepository;
    private final SeasonGroupScoreRepository groupScoreRepository;
    private final SeasonQueryService seasonQueryService;
    private final SeasonContributionMembershipService contributionMembershipService;
    private final GroupPresenceRevocationService presenceRevocationService;

    private final Clock clock;
    private final String appBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 그룹 생성에 필요한 저장소와 공통 시각을 주입받습니다. */
    public GroupMembershipService(
            UserRepository userRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            GroupJoinRestrictionRepository restrictionRepository,
            GroupInviteLinkRepository inviteLinkRepository,
            GroupAdminChangeRepository adminChangeRepository,
            GroupSeasonRepository groupSeasonRepository,
            GroupCreatureRepository groupCreatureRepository,
            SeasonGroupScoreRepository groupScoreRepository,
            SeasonQueryService seasonQueryService,
            SeasonContributionMembershipService contributionMembershipService,
            GroupPresenceRevocationService presenceRevocationService,
            Clock clock,
            @Value("${APP_BASE_URL:http://localhost:5173}") String appBaseUrl
    ) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionRepository = restrictionRepository;
        this.inviteLinkRepository = inviteLinkRepository;
        this.adminChangeRepository = adminChangeRepository;
        this.groupSeasonRepository = groupSeasonRepository;
        this.groupCreatureRepository = groupCreatureRepository;
        this.groupScoreRepository = groupScoreRepository;
        this.seasonQueryService = seasonQueryService;
        this.contributionMembershipService = contributionMembershipService;
        this.presenceRevocationService = presenceRevocationService;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * 새 그룹과 현재 시즌의 알 크리처까지 하나의 트랜잭션으로 생성합니다.
     *
     * @param loginUser 요청 사용자
     * @param request 그룹 입력
     * @return 생성된 그룹과 최초 초대 URL
     */
    @Transactional
    public CreateGroupResponse createGroup(LoginUser loginUser, CreateGroupRequest request) {
        // 시즌 공유 잠금을 획득하고 상태, 시간 경계를 검사한다.
        Season season = seasonQueryService.findCurrentSeasonForShare();

        // 조회 이후의 시간 경과를 감안하여 재검증.
        Instant now = Instant.now(clock);
        if (! season.isActiveAt(now)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "현재 시즌이 준비되지 않았습니다.");
        }

        // User 조회 및 그룹 생성
        User creator = userRepository.findByIdForUpdate(loginUser.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        String name = normalize(request.name());
        String description = request.description() == null ? "" : request.description().strip();

        if (groupRepository.existsByNormalizedName(name)) {
            throw conflict(ErrorCode.GROUP_NAME_ALREADY_USED, "이미 사용 중인 그룹 이름입니다.");
        }
        if (membershipRepository.existsByUserIdAndStatus(creator.getId(), "ACTIVE")) {
            throw conflict(ErrorCode.ALREADY_IN_GROUP, "이미 활성 그룹에 소속되어 있습니다.");
        }
        if (restrictionRepository.existsByUserIdAndRestrictedUntilAfter(creator.getId(), now)) {
            throw conflict(ErrorCode.JOIN_COOLDOWN_ACTIVE, "그룹 가입 제한 시간이 끝난 뒤 다시 시도해 주세요.");
        }
        Group group = groupRepository.save(Group.create(request.name().strip(), name, description, creator, now));
        GroupMembership membership = membershipRepository.save(GroupMembership.primary(group, creator, now));
        adminChangeRepository.save(GroupAdminChange.initialPrimary(group, creator, now));

        String invitationToken = createToken();
        inviteLinkRepository.save(GroupInviteLink.issue(group, Hashing.sha256(invitationToken), creator, now));
        GroupSeason groupSeason = groupSeasonRepository.save(GroupSeason.start(group, season, now));
        groupCreatureRepository.save(GroupCreature.egg(groupSeason, now));
        groupScoreRepository.save(SeasonGroupScore.zero(groupSeason, now));

        var summary = new CreateGroupResponse.GroupSummaryResponse(
                group.getId(), group.getName(), group.getDescription(), 1, 20, 19, true, null);
        var primary = new CreateGroupResponse.GroupMemberResponse(
                creator.getId(), loginUser.nickname(), "PRIMARY", membership.getJoinedAt());
        var myMembership = new CreateGroupResponse.MembershipResponse(
                membership.getId(), group.getId(), "PRIMARY", membership.getJoinedAt(), "ACTIVE");
        return new CreateGroupResponse(
                new CreateGroupResponse.GroupDetailResponse(summary, primary, myMembership),
                appBaseUrl + "/invite/" + invitationToken
        );
    }

    /** 공개 활성 그룹을 가입 가능 상태와 함께 페이지로 조회합니다. */
    @Transactional(readOnly = true)
    public Page<GroupSummaryResponse> listPublicGroups(LoginUser loginUser, String query, int page, int size) {
        Instant now = Instant.now(clock);
        boolean alreadyInGroup = membershipRepository.existsByUserIdAndStatus(loginUser.id(), "ACTIVE");
        boolean restricted = restrictionRepository.existsByUserIdAndRestrictedUntilAfter(loginUser.id(), now);
        String normalizedQuery = query == null ? "" : escapeLike(normalize(query));
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))
        );
        return groupRepository.findPublicGroups(normalizedQuery, pageable)
                .map(group -> summary(group, alreadyInGroup, restricted));
    }

    /** 인증 사용자를 지정한 공개 그룹의 일반 구성원으로 가입시킵니다. */
    @Transactional
    public MembershipResponse joinGroup(LoginUser loginUser, Long groupId) {
        return joinGroup(loginUser.id(), loginUser.nickname(), groupId, null);
    }

    /** 인증 사용자를 최종 유효성 검사를 통과한 초대 그룹에 가입시킵니다. */
    @Transactional
    public MembershipResponse joinInvitation(LoginUser loginUser, Long invitationId) {
        GroupInviteLink invitation = activeInvitation(invitationId);
        return joinGroup(
                loginUser.id(),
                loginUser.nickname(),
                invitation.getGroup().getId(),
                invitationId
        );
    }

    /** 가입 대기 계정이 초대 그룹에 가입하도록 시도하고 계정 생성과 분리된 실패 결과를 반환합니다. */
    @Transactional
    public InvitationJoinResult tryJoinInvitation(Long userId, String nickname, Long invitationId) {
        GroupInviteLink invitation;
        try {
            invitation = activeInvitation(invitationId);
        } catch (BusinessException exception) {
            return InvitationJoinResult.failure("INVITE_INVALID");
        }
        try {
            MembershipResponse membership = joinGroup(userId, nickname, invitation.getGroup().getId(), invitationId);
            return InvitationJoinResult.success(membership);
        } catch (BusinessException exception) {
            return InvitationJoinResult.failure(exception.code());
        }
    }

    /** 가입 대기 화면에 표시할 활성 초대 정보를 조회합니다. */
    @Transactional(readOnly = true)
    public java.util.Optional<com.team.sharedcreature.group.api.PublicInvitationResponse> findInvitation(Long invitationId) {
        return inviteLinkRepository.findByIdAndStatus(invitationId, "ACTIVE")
                .map(invitation -> {
                    Group group = invitation.getGroup();
                    boolean active = "ACTIVE".equals(group.getStatus());
                    return new com.team.sharedcreature.group.api.PublicInvitationResponse(
                            group.getId(),
                            group.getName(),
                            group.getDescription(),
                            group.getCurrentMemberCount(),
                            group.getCapacity(),
                            active && group.getCurrentMemberCount() < group.getCapacity(),
                            !active ? "INVITE_INVALID"
                                    : group.getCurrentMemberCount() >= group.getCapacity() ? "GROUP_FULL" : null
                    );
                });
    }

    /** 현재 사용자의 활성 재가입 제한 여부를 조회합니다. */
    @Transactional(readOnly = true)
    public boolean isJoinRestricted(Long userId) {
        return restrictionRepository.existsByUserIdAndRestrictedUntilAfter(userId, Instant.now(clock));
    }

    private GroupInviteLink activeInvitation(Long invitationId) {
        GroupInviteLink invitation = inviteLinkRepository.findByIdAndStatus(invitationId, "ACTIVE")
                .orElseThrow(() -> conflict(ErrorCode.INVITE_INVALID, "현재 사용할 수 없는 초대입니다."));
        if (!"ACTIVE".equals(invitation.getGroup().getStatus())) {
            throw conflict(ErrorCode.INVITE_INVALID, "현재 사용할 수 없는 초대입니다.");
        }
        return invitation;
    }

    private MembershipResponse joinGroup(Long userId, String nickname, Long groupId, Long invitationId) {
        // 사용자·그룹 잠금 전에 현재 시즌을 공유 잠금으로 확인합니다.
        Optional<Season> currentSeason = seasonQueryService.findCurrentSeasonForShareIfPresent();

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> notFound("가입할 그룹을 찾을 수 없습니다."));
        if (!"ACTIVE".equals(group.getStatus()) || !"PUBLIC".equals(group.getVisibility())) {
            throw notFound("가입할 그룹을 찾을 수 없습니다.");
        }
        if (invitationId != null
                && inviteLinkRepository.findActiveForUpdate(invitationId, groupId).isEmpty()) {
            throw conflict(ErrorCode.INVITE_INVALID, "현재 사용할 수 없는 초대입니다.");
        }
        if (membershipRepository.existsByUserIdAndStatus(userId, "ACTIVE")) {
            throw conflict(ErrorCode.ALREADY_IN_GROUP, "이미 활성 그룹에 소속되어 있습니다.");
        }
        Instant now = Instant.now(clock);
        if (restrictionRepository.existsByUserIdAndRestrictedUntilAfter(userId, now)) {
            throw conflict(ErrorCode.JOIN_COOLDOWN_ACTIVE, "그룹 가입 제한 시간이 끝난 뒤 다시 시도해 주세요.");
        }
        if (groupRepository.incrementMemberCountIfAvailable(groupId, now) != 1) {
            throw conflict(ErrorCode.GROUP_FULL, "그룹 정원이 가득 찼습니다.");
        }
        GroupMembership membership = membershipRepository.save(GroupMembership.member(group, user, now));

        // 같은 그룹의 기존 기여도만 ACTIVE로 복구하며 점수는 유지합니다.
        contributionMembershipService.synchronizeIfPresent(currentSeason, membership, now);

        return new MembershipResponse(
                membership.getId(),
                group.getId(),
                membership.getRole(),
                membership.getJoinedAt(),
                membership.getStatus(),
                nickname
        );
    }

    private GroupSummaryResponse summary(Group group, boolean alreadyInGroup, boolean restricted) {
        int remaining = Math.max(0, group.getCapacity() - group.getCurrentMemberCount());
        String reason = alreadyInGroup
                ? "ALREADY_IN_GROUP"
                : restricted ? "JOIN_COOLDOWN_ACTIVE" : remaining == 0 ? "GROUP_FULL" : null;
        return new GroupSummaryResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCurrentMemberCount(),
                group.getCapacity(),
                remaining,
                reason == null,
                reason
        );
    }

    private String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String createToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException conflict(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    /** 회원가입은 유지하면서 초대 가입 성공 또는 표준 실패 사유를 전달합니다. */
    public record InvitationJoinResult(MembershipResponse membership, String failureCode) {
        /** 초대 가입 성공 결과를 생성합니다. */
        public static InvitationJoinResult success(MembershipResponse membership) {
            return new InvitationJoinResult(membership, null);
        }

        /** 초대 가입 실패 결과를 생성합니다. */
        public static InvitationJoinResult failure(String code) {
            return new InvitationJoinResult(null, code);
        }

        /** 멤버십이 생성되었는지 반환합니다. */
        public boolean joined() {
            return membership != null;
        }
    }

    /**
     * 현재 로그인한 사용자를 소속 그룹에서 자진 탈퇴시킴
     *  - 사용자 -> 그룹 -> 초대 링크(1인 종료 시) -> 멤버십 -> 가입 제한 순서로 잠금
     *  - 마지막 주 관리자(PRIMARY)라면 그룹 및 현재 시즌 참여까지 함께 종료
     */
    @Transactional
    public LeaveMyGroupResponse leaveMyGroup(Long userId) {
        // 1. 잠금 전에는 활성 그룹 ID만 확인함
        Long groupId = membershipRepository.findActiveGroupIdByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));

        // 탈퇴 시에도 사용자·그룹 잠금보다 먼저 시즌 공유 잠금을 잡습니다.
        Optional<Season> currentSeason = seasonQueryService.findCurrentSeasonForShareIfPresent();

        // 2. 사용자 행을 먼저 잠금
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));

        // 3. 그룹 행을 잠그고 활성 상태를 확인
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!"ACTIVE".equals(group.getStatus())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        // 4. 유일한 주 관리자(PRIMARY) 라면 링크 무효화 : 먼저 잠금
        boolean lastPrimaryCandidate =
                group.getCurrentMemberCount() == 1 &&
                group.getPrimaryAdminUser() != null &&
                userId.equals(group.getPrimaryAdminUser().getId());

        Optional<GroupInviteLink> activeInvitation = lastPrimaryCandidate
                ? inviteLinkRepository.findActiveByGroupIdForUpdate(groupId)
                : Optional.empty();

        // 5. 활성 멤버십 ID를 재조회하고 쓰기 잠금 획득
        Long membershipId = membershipRepository
                .findActiveMembershipId(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));

        GroupMembership membership = membershipRepository
                .findByIdForUpdate(membershipId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));

        // 6. 잠금 이후 현재 사용자, 그룹, 멤버십의 일치 여부 다시 검사
        if (!"ACTIVE".equals(membership.getStatus()) ||
            !userId.equals(membership.getUser().getId()) ||
            !groupId.equals(membership.getGroup().getId())
        ) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        boolean isPrimary = "PRIMARY".equals(membership.getRole());
        int memberCount = group.getCurrentMemberCount();

        // 7. 다른 활성 구성원이 있다면 주 관리자(PRIMARY)는 이전 후에 탈퇴 가능
        if (isPrimary && memberCount > 1) {
            throw new BusinessException(ErrorCode.PRIMARY_TRANSFER_REQUIRED);
        }

        // 마지막 회원이 주 관리자(PRIMARY)인지 확인
        if (memberCount < 1 || (memberCount == 1 && !isPrimary)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Instant now = Instant.now(clock);

        // 8. 멤버십을 LEFT로 종료. 기존 이력은 삭제하지 않음
        membership.leaveBySelf(user, now);

        // Rollback된 탈퇴가 Presence를 제거하지 않도록 Commit 이후 권한 회수를 예약합니다.
        presenceRevocationService.revokeAfterCommit(userId, now);

        // 멤버십·기여도 상태가 같은 트랜잭션에서 Commit되게 합니다.
        contributionMembershipService.synchronizeIfPresent(currentSeason, membership, now);

        if (isPrimary) {
            // 마지막 주 관리자(PRIMARY)의 탈퇴는 그룹 종료로 처리됨
            group.deactivateAfterLastMemberLeaves(now);

            // 마지막 활성 초대 링크도 무효화시키고 떠나기
            activeInvitation.ifPresent(link -> link.invalidate(now));
        } else {
            // 일반 회원의 탈퇴는 그룹 인원 한 명 감소 갱신
            group.decrementMemberCount(now);
        }

        // 10. 24시간 제한 : 사용자별 기존 제한 행이 있으면 재사용
        GroupJoinRestriction restriction = restrictionRepository
                .findByUserIdForUpdate(userId)
                .orElse(null);

        if (restriction == null) {
            // 최초 탈퇴는 24시간 제한 행을 생성
            restriction = GroupJoinRestriction.forDeparture(user, membership, "SELF", now);
            restrictionRepository.save(restriction);
        } else {
            // 재탈퇴 시 기존 UNIQUE 행의 근거와 기간을 갱신
            restriction.renew(membership, "SELF", now);
        }

        // 11. 마지막 주 관리자(PRIMARY)라면 시즌 참여와 크리처도 종료하기
        if (isPrimary) {
            closeCurrentGroupSeason(groupId, now);
        }

        // Commit
        return new LeaveMyGroupResponse(
                groupId, userId, now, "SELF", now.plusSeconds(24L * 60 * 60), isPrimary
        );
    }

    /**
     * 비활성화된 그룹의 현재 시즌 참여와 크리처를 종료함
     *  - 각각 쓰기 잠금 획득
     *  - 크리처 경험치, 기존 시즌 점수, 개인 기여 이력은 보존함
     */
    private void closeCurrentGroupSeason(Long groupId, Instant now) {
        // 종료되지 않은 그룹 시즌 참여만 처리
        groupSeasonRepository.findOpenByGroupIdForUpdate(groupId, now)
                .ifPresent(groupSeason -> {
                    // 현재 시즌에 연결된 크리처도 쓰기 잠금으로 조회
                    GroupCreature creature = groupCreatureRepository
                            .findForUpdateByGroupSeasonId(groupSeason.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "그룹 시즌에 연결된 크리처가 없습니다 : " + groupSeason.getId()
                            ));

                    // 기존 성장 이력을 보존하고 상태만 종료
                    groupSeason.endForGroupClosure(now);
                    creature.archiveForGroupClosure(now);
                });

    }
}
