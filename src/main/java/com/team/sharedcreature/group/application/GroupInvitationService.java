package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.application.AuthService;
import com.team.sharedcreature.auth.application.PendingSignup;
import com.team.sharedcreature.group.api.NavigationResponse;
import com.team.sharedcreature.group.api.PublicInvitationResponse;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 공개 초대 조회와 세션 문맥 수락·거절 흐름을 처리합니다. */
@Service
public class GroupInvitationService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    private final GroupInviteLinkRepository inviteLinkRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupMembershipService membershipService;
    private final Clock clock;

    /** 초대 검증과 즉시 가입에 필요한 협력 객체를 주입받습니다. */
    public GroupInvitationService(
            GroupInviteLinkRepository inviteLinkRepository,
            GroupMembershipRepository membershipRepository,
            GroupMembershipService membershipService,
            Clock clock
    ) {
        this.inviteLinkRepository = inviteLinkRepository;
        this.membershipRepository = membershipRepository;
        this.membershipService = membershipService;
        this.clock = clock;
    }

    /** 활성 Token의 공개 그룹 상태를 조회합니다. */
    @Transactional(readOnly = true)
    public PublicInvitationResponse getPublicInvitation(String token, HttpSession session) {
        return response(activeInvitation(token), session);
    }

    /** 초대를 세션에 연결하거나 인증된 무소속 사용자를 즉시 가입시킵니다. */
    @Transactional
    public NavigationResponse acceptInvitation(String token, HttpSession session) {
        GroupInviteLink invitation = activeInvitation(token);
        if (!"ACTIVE".equals(invitation.getGroup().getStatus())) {
            throw conflict(ErrorCode.INVITE_INVALID, "현재 사용할 수 없는 초대입니다.");
        }
        Object login = session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        if (login instanceof LoginUser loginUser) {
            var current = membershipRepository.findActiveWithGroupByUserId(loginUser.id());
            if (current.isPresent()) {
                if (current.get().getGroup().getId().equals(invitation.getGroup().getId())) {
                    return new NavigationResponse("/creature", null);
                }
                throw conflict(ErrorCode.ALREADY_IN_GROUP, "이미 활성 그룹에 소속되어 있습니다.");
            }
            if (invitation.getGroup().getCurrentMemberCount() >= invitation.getGroup().getCapacity()) {
                throw conflict(ErrorCode.GROUP_FULL, "그룹 정원이 가득 찼습니다.");
            }
            var membership = membershipService.joinInvitation(loginUser, invitation.getId());
            var roles = new HashSet<>(loginUser.roles());
            roles.add(membership.role());
            session.setAttribute(
                    SessionAuthenticationFilter.LOGIN_USER,
                    new LoginUser(loginUser.id(), loginUser.nickname(), Set.copyOf(roles))
            );
            session.removeAttribute(InvitationSessionContext.SESSION_KEY);
            return new NavigationResponse("/creature", null);
        }

        if (invitation.getGroup().getCurrentMemberCount() >= invitation.getGroup().getCapacity()) {
            throw conflict(ErrorCode.GROUP_FULL, "그룹 정원이 가득 찼습니다.");
        }

        session.setAttribute(
                InvitationSessionContext.SESSION_KEY,
                new InvitationSessionContext(invitation.getId())
        );
        Object pending = session.getAttribute(AuthService.PENDING_SIGNUP);
        if (pending instanceof PendingSignup pendingSignup) {
            if (pendingSignup.expiresAt().isAfter(Instant.now(clock))) {
                session.setAttribute(AuthService.PENDING_SIGNUP, pendingSignup.withInviteLinkId(invitation.getId()));
                return new NavigationResponse("/signup", null);
            }
            session.removeAttribute(AuthService.PENDING_SIGNUP);
        }
        return new NavigationResponse("/login", null);
    }

    /** 초대 문맥과 가입 대기를 제거하고 현재 인증 상태에 맞는 일반 경로를 반환합니다. */
    @Transactional(readOnly = true)
    public NavigationResponse rejectInvitation(String token, HttpSession session) {
        session.removeAttribute(InvitationSessionContext.SESSION_KEY);
        session.removeAttribute(AuthService.PENDING_SIGNUP);
        activeInvitation(token);

        Object login = session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        if (login instanceof LoginUser loginUser) {
            String route = membershipRepository.existsByUserIdAndStatus(loginUser.id(), "ACTIVE")
                    ? "/creature"
                    : "/groups/select";
            return new NavigationResponse(route, "INVITATION_REJECTED");
        }
        return new NavigationResponse("/login", "INVITATION_REJECTED");
    }

    private GroupInviteLink activeInvitation(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "초대 Token 형식을 확인해 주세요.");
        }
        GroupInviteLink invitation = inviteLinkRepository.findByTokenHash(Hashing.sha256(token))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "초대 정보를 찾을 수 없습니다."
                ));
        if (!"ACTIVE".equals(invitation.getStatus())) {
            throw conflict(ErrorCode.INVITE_INVALID, "현재 사용할 수 없는 초대입니다.");
        }
        return invitation;
    }

    private PublicInvitationResponse response(GroupInviteLink invitation, HttpSession session) {
        var group = invitation.getGroup();
        String blockedReason = blockedReason(group, session);
        return new PublicInvitationResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCurrentMemberCount(),
                group.getCapacity(),
                blockedReason == null,
                blockedReason
        );
    }

    private String blockedReason(com.team.sharedcreature.group.domain.Group group, HttpSession session) {
        if (!"ACTIVE".equals(group.getStatus())) {
            return "INVITE_INVALID";
        }
        Object login = session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        if (login instanceof LoginUser loginUser) {
            var membership = membershipRepository.findActiveWithGroupByUserId(loginUser.id());
            if (membership.isPresent()) {
                return "ALREADY_IN_GROUP";
            }
            if (membershipService.isJoinRestricted(loginUser.id())) {
                return "JOIN_COOLDOWN_ACTIVE";
            }
        }
        if (group.getCurrentMemberCount() >= group.getCapacity()) {
            return "GROUP_FULL";
        }
        return null;
    }

    private BusinessException conflict(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }
}
