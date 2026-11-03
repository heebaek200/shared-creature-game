package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.application.AuthService;
import com.team.sharedcreature.group.api.NavigationResponse;
import com.team.sharedcreature.group.api.PublicInvitationResponse;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/** 공개 초대 조회와 세션 문맥 수락·거절 흐름을 처리합니다. */
@Service
public class GroupInvitationService {

    private final GroupInviteLinkRepository inviteLinkRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupMembershipService membershipService;

    /** 초대 검증과 즉시 가입에 필요한 협력 객체를 주입받습니다. */
    public GroupInvitationService(
            GroupInviteLinkRepository inviteLinkRepository,
            GroupMembershipRepository membershipRepository,
            GroupMembershipService membershipService
    ) {
        this.inviteLinkRepository = inviteLinkRepository;
        this.membershipRepository = membershipRepository;
        this.membershipService = membershipService;
    }

    /** 활성 Token의 공개 그룹 상태를 조회합니다. */
    @Transactional(readOnly = true)
    public PublicInvitationResponse getPublicInvitation(String token) {
        return response(activeInvitation(token));
    }

    /** 초대를 세션에 연결하거나 인증된 무소속 사용자를 즉시 가입시킵니다. */
    @Transactional
    public NavigationResponse acceptInvitation(String token, HttpSession session) {
        GroupInviteLink invitation = activeInvitation(token);
        if (!"ACTIVE".equals(invitation.getGroup().getStatus())) {
            throw conflict("INVITE_INVALID", "현재 사용할 수 없는 초대입니다.");
        }
        if (invitation.getGroup().getCurrentMemberCount() >= invitation.getGroup().getCapacity()) {
            throw conflict("GROUP_FULL", "그룹 정원이 가득 찼습니다.");
        }

        Object login = session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        if (login instanceof LoginUser loginUser) {
            var current = membershipRepository.findActiveWithGroupByUserId(loginUser.id());
            if (current.isPresent()) {
                if (current.get().getGroup().getId().equals(invitation.getGroup().getId())) {
                    return new NavigationResponse("/creature", null);
                }
                throw conflict("ALREADY_IN_GROUP", "이미 활성 그룹에 소속되어 있습니다.");
            }
            var membership = membershipService.joinGroup(loginUser, invitation.getGroup().getId());
            var roles = new HashSet<>(loginUser.roles());
            roles.add(membership.role());
            session.setAttribute(
                    SessionAuthenticationFilter.LOGIN_USER,
                    new LoginUser(loginUser.id(), loginUser.nickname(), Set.copyOf(roles))
            );
            session.removeAttribute(InvitationSessionContext.SESSION_KEY);
            return new NavigationResponse("/creature", null);
        }

        session.setAttribute(
                InvitationSessionContext.SESSION_KEY,
                new InvitationSessionContext(invitation.getId())
        );
        return new NavigationResponse(
                session.getAttribute(AuthService.PENDING_SIGNUP) == null ? "/login" : "/signup",
                null
        );
    }

    /** 초대 문맥과 가입 대기를 제거하고 현재 인증 상태에 맞는 일반 경로를 반환합니다. */
    @Transactional(readOnly = true)
    public NavigationResponse rejectInvitation(String token, HttpSession session) {
        activeInvitation(token);
        session.removeAttribute(InvitationSessionContext.SESSION_KEY);
        session.removeAttribute(AuthService.PENDING_SIGNUP);

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
        return inviteLinkRepository.findByTokenHashAndStatus(Hashing.sha256(token), "ACTIVE")
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "초대 정보를 찾을 수 없습니다."
                ));
    }

    private PublicInvitationResponse response(GroupInviteLink invitation) {
        var group = invitation.getGroup();
        boolean groupActive = "ACTIVE".equals(group.getStatus());
        boolean acceptable = groupActive && group.getCurrentMemberCount() < group.getCapacity();
        return new PublicInvitationResponse(
                group.getId(),
                group.getName(),
                group.getCurrentMemberCount(),
                group.getCapacity(),
                groupActive ? "ACTIVE" : "GROUP_UNAVAILABLE",
                acceptable
        );
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
