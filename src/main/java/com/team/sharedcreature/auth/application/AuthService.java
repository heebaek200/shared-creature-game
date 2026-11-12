package com.team.sharedcreature.auth.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
import com.team.sharedcreature.auth.domain.SocialAccount;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.*;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.application.InvitationSessionContext;
import com.team.sharedcreature.group.api.PublicInvitationResponse;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * OAuth 시도, 가입 대기, 내부 회원 연결과 로그인 세션 흐름을 처리합니다.
 */
@Service
public class AuthService {

    public static final String PENDING_SIGNUP = "PENDING_SIGNUP";
    public static final String ONBOARDING_REASON = "ONBOARDING_REASON";
    private static final String OAUTH_NONCE = "OAUTH_NONCE";

    private final OauthLoginAttemptRepository attemptRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupInviteLinkRepository inviteLinkRepository;
    private final GroupJoinRestrictionRepository restrictionRepository;
    private final GroupMembershipService groupMembershipService;
    private final List<SocialLoginClient> socialLoginClients;
    private final InitialSuperAdminProperties initialSuperAdmin;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private static final Object[] SESSION_LIMIT_LOCKS = new Object[64];
    static {
        Arrays.setAll(SESSION_LIMIT_LOCKS, index -> new Object());
    }

    private final SecureRandom secureRandom = new SecureRandom();

    /** 인증 흐름에 필요한 저장소와 공급자 Port를 주입받습니다. */
    public AuthService(
            OauthLoginAttemptRepository attemptRepository,
            SocialAccountRepository socialAccountRepository,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            GroupMembershipRepository membershipRepository,
            GroupInviteLinkRepository inviteLinkRepository,
            GroupJoinRestrictionRepository restrictionRepository,
            GroupMembershipService groupMembershipService,
            List<SocialLoginClient> socialLoginClients,
            InitialSuperAdminProperties initialSuperAdmin,
            TransactionTemplate transactionTemplate,
            Clock clock, FindByIndexNameSessionRepository<? extends Session> sessionRepository
    ) {
        this.attemptRepository = attemptRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.membershipRepository = membershipRepository;
        this.inviteLinkRepository = inviteLinkRepository;
        this.restrictionRepository = restrictionRepository;
        this.groupMembershipService = groupMembershipService;
        this.socialLoginClients = socialLoginClients;
        this.initialSuperAdmin = initialSuperAdmin;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.sessionRepository = sessionRepository;
    }

    /** 소셜 OAuth 시도를 저장하고 공급자 Authorization URL을 반환합니다. */
    @Transactional
    public OAuthAttemptResult createOAuthAttempt(String provider, String returnRoute, HttpSession session) {
        SocialLoginClient client = findSocialLoginClient(provider);
        Instant now = Instant.now(clock);
        String state = token();
        String nonce = token();
        GroupInviteLink invitation = null;
        String intent = "LOGIN";
        String resolvedRoute = returnRoute == null ? "DEFAULT" : returnRoute;
        if ("INVITE".equals(resolvedRoute)) {
            Object context = session.getAttribute(InvitationSessionContext.SESSION_KEY);
            if (!(context instanceof InvitationSessionContext invitationContext)) {
                throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "수락한 초대 문맥이 필요합니다.");
            }
            invitation = inviteLinkRepository.findByIdAndStatus(invitationContext.inviteLinkId(), "ACTIVE")
                    .orElseThrow(() -> conflict(ErrorCode.INVITE_INVALID, "현재 사용할 수 없는 초대입니다."));
            intent = "INVITE";
        }
        attemptRepository.save(OauthLoginAttempt.start(
                Hashing.sha256(state),
                Hashing.sha256(session.getId()),
                provider,
                intent,
                invitation,
                resolvedRoute,
                now
        ));
        session.setAttribute(OAUTH_NONCE, nonce);
        return new OAuthAttemptResult(client.authorizationUrl(state, nonce), now.plusSeconds(600));
    }

    private SocialLoginClient findSocialLoginClient(String provider) {
        return socialLoginClients.stream()
                .filter(client -> provider.equals(client.provider()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "지원하지 않는 소셜 로그인 공급자입니다."
                ));
    }

    /** Callback의 state·시작 세션을 검증한 뒤 기존 로그인 또는 가입 대기로 전환합니다. */
    public String handleCallback(String state, String code, HttpSession session) {
        Instant now = Instant.now(clock);
        String stateHash = Hashing.sha256(state);
        String sessionHash = Hashing.sha256(session.getId());

        // OAuth를 시작한 요청을 찾아 state와 세션을 검증하고 공급자를 확인합니다.
        String provider = transactionTemplate.execute(status -> {
            OauthLoginAttempt attempt = attemptRepository.findByStateHash(stateHash)
                    .orElseThrow(() -> conflict(
                            ErrorCode.OAUTH_STATE_INVALID,
                            "유효하지 않은 로그인 요청입니다."
                    ));

            validateAttempt(attempt, sessionHash, now);
            return attempt.getProvider();
        });

        String nonce = (String) session.getAttribute(OAUTH_NONCE);

        SocialLoginClient client = findSocialLoginClient(provider);

        SocialLoginClient.SocialProfile profile = client.exchange(code, nonce, state);

        return transactionTemplate.execute(status ->
                completeCallback(stateHash, sessionHash, profile, session, now)
        );
    }

    private void validateAttempt(OauthLoginAttempt attempt, String sessionHash, Instant now) {
        if (!"PENDING".equals(attempt.getStatus())) {
            throw conflict(ErrorCode.OAUTH_STATE_INVALID, "이미 사용한 로그인 요청입니다.");
        }
        if (attempt.getExpiresAt().isBefore(now)) {
            throw conflict(ErrorCode.OAUTH_SESSION_EXPIRED, "OAuth 인증 세션이 만료됐습니다.");
        }
        if (!attempt.getInitiatingSessionHash().equals(sessionHash)) {
            throw conflict(ErrorCode.OAUTH_SESSION_MISMATCH, "로그인을 시작한 브라우저 세션과 다릅니다.");
        }
    }

    private String completeCallback(
            String stateHash,
            String sessionHash,
            SocialLoginClient.SocialProfile profile,
            HttpSession session,
            Instant now
    ) {
        OauthLoginAttempt attempt = attemptRepository.findByStateHashForUpdate(stateHash)
                .orElseThrow(() -> conflict(ErrorCode.OAUTH_STATE_INVALID, "유효하지 않은 로그인 요청입니다."));
        validateAttempt(attempt, sessionHash, now);
        Long invitationId = "INVITE".equals(attempt.getIntent()) ? currentInvitationId(session) : null;
        GroupInviteLink latestInvitation = invitationId == null
                ? null
                : inviteLinkRepository.findById(invitationId).orElse(null);
        attempt.replaceInviteLink(latestInvitation);
        attempt.consume(now);
        if (invitationId == null) {
            session.removeAttribute(ONBOARDING_REASON);
        }

        String provider = attempt.getProvider();
        var account = socialAccountRepository.findByProviderAndProviderUserId(
                provider,
                profile.providerUserId()
        );
        if (account.isPresent()) {
            // 기존 회원 분기
            User user = account.get().getUser();

            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
            }

            // 기존 회원의 OAuth 로그인 성공 시각을 갱신합니다.
            user.recordLogin(now);

            // 로그인 및 초대 가입 처리
            String nickname = userProfileRepository.findByUserId(user.getId()).map(UserProfile::getNickname).orElse("회원");
            joinExistingInvitation(invitationId, user, nickname, session);
            Set<String> roles = currentRoles(user);
            session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(user.getId(), nickname, roles));
            enforceSessionLimit(user.getId(), session);
            session.removeAttribute(OAUTH_NONCE);
            return "/";
        }

        String normalizedEmail = profile.email().strip().toLowerCase(Locale.ROOT);
        if (socialAccountRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
            throw conflict(ErrorCode.SOCIAL_EMAIL_PROVIDER_CONFLICT, "이미 다른 소셜 공급자로 가입한 이메일입니다.");
        }
        session.setAttribute(PENDING_SIGNUP, new PendingSignup(
                provider,
                profile.providerUserId(),
                profile.email(),
                profile.emailVerified(),
                profile.suggestedNickname(),
                invitationId,
                now.plusSeconds(1800)));
        session.removeAttribute(OAUTH_NONCE);
        return "/";
    }

    /** 가입 대기 사용자의 닉네임을 확정하고 내부 계정을 생성합니다. */
    @Transactional
    public LoginUser completeSignup(String nickname, HttpSession session) {
        Instant now = Instant.now(clock);
        Object attribute = session.getAttribute(PENDING_SIGNUP);
        if (!(attribute instanceof PendingSignup pending) || pending.expiresAt().isBefore(now)) {
            session.removeAttribute(PENDING_SIGNUP);
            throw conflict(ErrorCode.SIGNUP_CONTEXT_EXPIRED, "가입 대기 시간이 만료됐습니다.");
        }
        String normalizedNickname = normalize(nickname);
        if (userProfileRepository.existsByNormalizedNickname(normalizedNickname)) {
            throw conflict(ErrorCode.NICKNAME_ALREADY_USED, "이미 사용 중인 닉네임입니다.");
        }
        String globalRole = initialSuperAdmin.matches(pending.provider(), pending.providerUserId())
                && !userRepository.existsByGlobalRoleAndStatus("SUPER_ADMIN", "ACTIVE")
                ? "SUPER_ADMIN"
                : "USER";
        User user = userRepository.save(User.register(globalRole, now));
        socialAccountRepository.save(SocialAccount.link(
                user, pending.provider(), pending.providerUserId(), pending.email(), pending.emailVerified(), now));
        userProfileRepository.save(UserProfile.create(user, nickname.strip(), normalizedNickname, now));
        if (pending.inviteLinkId() != null) {
            GroupMembershipService.InvitationJoinResult result =
                    groupMembershipService.tryJoinInvitation(user.getId(), nickname.strip(), pending.inviteLinkId());
            if (result.joined()) {
                session.removeAttribute(ONBOARDING_REASON);
            } else {
                session.setAttribute(ONBOARDING_REASON, result.failureCode());
            }
        }
        LoginUser loginUser = new LoginUser(user.getId(), nickname.strip(), currentRoles(user));
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, loginUser);
        enforceSessionLimit(user.getId(), session);
        session.removeAttribute(PENDING_SIGNUP);
        session.removeAttribute(InvitationSessionContext.SESSION_KEY);
        return loginUser;
    }

    /** 가입 대기 세션에 연결된 현재 초대 표시 정보를 반환합니다. */
    @Transactional(readOnly = true)
    public Optional<PublicInvitationResponse> signupInvitation(PendingSignup pending) {
        if (pending.inviteLinkId() == null) {
            return Optional.empty();
        }
        return groupMembershipService.findInvitation(pending.inviteLinkId());
    }

    private void joinExistingInvitation(Long invitationId, User user, String nickname, HttpSession session) {
        if (invitationId == null) {
            return;
        }
        GroupInviteLink invitation = inviteLinkRepository.findById(invitationId).orElse(null);
        if (invitation == null) {
            session.setAttribute(ONBOARDING_REASON, "INVITE_INVALID");
            return;
        }
        var current = membershipRepository.findActiveWithGroupByUserId(user.getId());
        if (current.isPresent() && current.get().getGroup().getId().equals(invitation.getGroup().getId())) {
            session.removeAttribute(ONBOARDING_REASON);
            session.removeAttribute(InvitationSessionContext.SESSION_KEY);
            return;
        }
        GroupMembershipService.InvitationJoinResult result =
                groupMembershipService.tryJoinInvitation(user.getId(), nickname, invitationId);
        if (result.joined()) {
            session.removeAttribute(ONBOARDING_REASON);
        } else {
            session.setAttribute(ONBOARDING_REASON, result.failureCode());
        }
        session.removeAttribute(InvitationSessionContext.SESSION_KEY);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private Set<String> currentRoles(User user) {
        var roles = new java.util.HashSet<String>();
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
            roles.add("SUPER_ADMIN");
        }
        membershipRepository.findByUserIdAndStatus(user.getId(), "ACTIVE")
                .ifPresent(membership -> roles.add(membership.getRole()));
        return Set.copyOf(roles);
    }

    private Long currentInvitationId(HttpSession session) {
        Object context = session.getAttribute(InvitationSessionContext.SESSION_KEY);
        return context instanceof InvitationSessionContext invitationContext
                ? invitationContext.inviteLinkId()
                : null;
    }

    /** 현재 사용자의 활성 그룹 멤버십을 Route Guard 응답용으로 조회합니다. */
    @Transactional(readOnly = true)
    public Optional<CurrentMembership> currentMembership(Long userId) {
        return membershipRepository.findByUserIdAndStatus(userId, "ACTIVE")
                .map(membership -> new CurrentMembership(
                        membership.getId(),
                        membership.getGroup().getId(),
                        membership.getRole(),
                        membership.getStatus(),
                        membership.getJoinedAt()
                ));
    }

    /** 현재 사용자의 활성 그룹 재가입 제한 종료 시각을 조회합니다. */
    @Transactional(readOnly = true)
    public Optional<Instant> currentRestrictionUntil(Long userId) {
        return restrictionRepository.findFirstByUserIdAndRestrictedUntilAfterOrderByRestrictedUntilDesc(
                userId,
                Instant.now(clock)
        ).map(com.team.sharedcreature.group.domain.GroupJoinRestriction::getRestrictedUntil);
    }

    /** 초대 가입 실패 사유를 다음 인증 응답에서 한 번 반환하도록 소비합니다. */
    public String consumeOnboardingReason(HttpSession session) {
        Object reason = session.getAttribute(ONBOARDING_REASON);
        session.removeAttribute(ONBOARDING_REASON);
        return reason instanceof String value ? value : null;
    }

    private String token() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException conflict(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }

    /** OAuth 시작 결과입니다. */
    public record OAuthAttemptResult(String authorizationUrl, Instant expiresAt) {
    }

    /** Route Guard에 제공할 활성 멤버십 최소 정보입니다. */
    public record CurrentMembership(
            Long membershipId,
            Long groupId,
            String role,
            String status,
            Instant joinedAt
    ) {
    }

    private void enforceSessionLimit(Long userId, HttpSession currentSession) {
        String principalName = userId.toString();
        currentSession.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principalName);
        Object lock = SESSION_LIMIT_LOCKS[Math.floorMod(userId.hashCode(), SESSION_LIMIT_LOCKS.length)];

        synchronized (lock) {
            Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(principalName);

            List<Map.Entry<String, ? extends Session>> previousSessions = new ArrayList<>(sessions.entrySet());
            previousSessions.removeIf(entry -> entry.getKey().equals(currentSession.getId()));
            previousSessions.sort(Comparator.comparing(entry -> entry.getValue().getLastAccessedTime()));

            while (previousSessions.size() > 2) {
                Map.Entry<String, ? extends Session> oldest = previousSessions.remove(0);
                sessionRepository.deleteById(oldest.getKey());
            }
        }
    }


}
