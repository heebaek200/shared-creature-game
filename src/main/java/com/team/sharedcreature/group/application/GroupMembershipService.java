package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.CreateGroupRequest;
import com.team.sharedcreature.group.api.CreateGroupResponse;
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
import org.springframework.http.HttpStatus;
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
        Instant now = Instant.now(clock);
        User creator = userRepository.findById(loginUser.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다."));
        String name = normalize(request.name());
        String description = request.description() == null ? "" : request.description().strip();

        if (groupRepository.existsByNormalizedName(name)) {
            throw conflict("GROUP_NAME_ALREADY_USED", "이미 사용 중인 그룹 이름입니다.");
        }
        if (membershipRepository.existsByUserIdAndStatus(creator.getId(), "ACTIVE")) {
            throw conflict("ALREADY_IN_GROUP", "이미 활성 그룹에 소속되어 있습니다.");
        }
        if (restrictionRepository.existsByUserIdAndRestrictedUntilAfter(creator.getId(), now)) {
            throw conflict("JOIN_COOLDOWN_ACTIVE", "그룹 가입 제한 시간이 끝난 뒤 다시 시도해 주세요.");
        }

        Season season = seasonRepository.findFirstByStatusOrderByStartAtDesc("ACTIVE")
                .orElseThrow(() -> conflict("CURRENT_SEASON_UNAVAILABLE", "현재 진행 중인 시즌이 없습니다."));
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

    private String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private String createToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
