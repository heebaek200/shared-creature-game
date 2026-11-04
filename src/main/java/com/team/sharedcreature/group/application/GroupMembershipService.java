package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.CreateGroupRequest;
import com.team.sharedcreature.group.api.CreateGroupResponse;
import com.team.sharedcreature.group.api.GroupSummaryResponse;
import com.team.sharedcreature.group.api.MembershipResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupAdminChange;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.*;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

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
    private final SeasonRepository seasonRepository;
    private final GroupSeasonRepository groupSeasonRepository;
    private final GroupCreatureRepository groupCreatureRepository;
    private final SeasonGroupScoreRepository groupScoreRepository;
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
            SeasonRepository seasonRepository,
            GroupSeasonRepository groupSeasonRepository,
            GroupCreatureRepository groupCreatureRepository,
            SeasonGroupScoreRepository groupScoreRepository,
            Clock clock,
            @Value("${APP_BASE_URL:http://localhost:5173}") String appBaseUrl
    ) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.restrictionRepository = restrictionRepository;
        this.inviteLinkRepository = inviteLinkRepository;
        this.adminChangeRepository = adminChangeRepository;
        this.seasonRepository = seasonRepository;
        this.groupSeasonRepository = groupSeasonRepository;
        this.groupCreatureRepository = groupCreatureRepository;
        this.groupScoreRepository = groupScoreRepository;
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
        Season season = seasonRepository.findForShareByStatus("ACTIVE", PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElseThrow(() -> notFound("현재 시즌을 찾을 수 없습니다."));
        Instant now = Instant.now(clock);
        if (!"ACTIVE".equals(season.getStatus()) || !now.isBefore(season.getEndAt())) {
            throw notFound("현재 시즌을 찾을 수 없습니다.");
        }
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
}
