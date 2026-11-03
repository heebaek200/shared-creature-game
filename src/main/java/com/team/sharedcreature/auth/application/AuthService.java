package com.team.sharedcreature.auth.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
import com.team.sharedcreature.auth.domain.SocialAccount;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.*;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * OAuth 시도, 가입 대기, 내부 회원 연결과 로그인 세션 흐름을 처리합니다.
 */
@Service
public class AuthService {

    public static final String PENDING_SIGNUP = "PENDING_SIGNUP";
    private static final String OAUTH_NONCE = "OAUTH_NONCE";

    private final OauthLoginAttemptRepository attemptRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final GroupMembershipRepository membershipRepository;
    private final SocialLoginClient socialLoginClient;
    private final InitialSuperAdminProperties initialSuperAdmin;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 인증 흐름에 필요한 저장소와 공급자 Port를 주입받습니다. */
    public AuthService(
            OauthLoginAttemptRepository attemptRepository,
            SocialAccountRepository socialAccountRepository,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            GroupMembershipRepository membershipRepository,
            SocialLoginClient socialLoginClient,
            InitialSuperAdminProperties initialSuperAdmin,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.attemptRepository = attemptRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.membershipRepository = membershipRepository;
        this.socialLoginClient = socialLoginClient;
        this.initialSuperAdmin = initialSuperAdmin;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Google OAuth 시도를 저장하고 공급자 Authorization URL을 반환합니다. */
    @Transactional
    public OAuthAttemptResult createOAuthAttempt(String provider, String returnRoute, HttpSession session) {
        if (!"GOOGLE".equals(provider)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FIELD_VALUE", "현재 Google 로그인만 지원합니다.");
        }
        Instant now = Instant.now(clock);
        String state = token();
        String nonce = token();
        attemptRepository.save(OauthLoginAttempt.start(
                Hashing.sha256(state),
                Hashing.sha256(session.getId()),
                provider,
                "LOGIN",
                returnRoute == null ? "DEFAULT" : returnRoute,
                now
        ));
        session.setAttribute(OAUTH_NONCE, nonce);
        return new OAuthAttemptResult(socialLoginClient.authorizationUrl(state, nonce), now.plusSeconds(600));
    }

    /** Callback의 state·시작 세션을 검증한 뒤 기존 로그인 또는 가입 대기로 전환합니다. */
    public String handleCallback(String state, String code, HttpSession session) {
        Instant now = Instant.now(clock);
        String stateHash = Hashing.sha256(state);
        String sessionHash = Hashing.sha256(session.getId());
        transactionTemplate.executeWithoutResult(status -> validateAttempt(stateHash, sessionHash, now));
        String nonce = (String) session.getAttribute(OAUTH_NONCE);

        // 외부 Google 호출은 DB Connection과 Transaction을 점유하지 않습니다.
        SocialLoginClient.SocialProfile profile = socialLoginClient.exchange(code, nonce);

        return transactionTemplate.execute(status -> completeCallback(stateHash, sessionHash, profile, session, now));
    }

    private void validateAttempt(String stateHash, String sessionHash, Instant now) {
        OauthLoginAttempt attempt = attemptRepository.findByStateHash(stateHash)
                .orElseThrow(() -> conflict("OAUTH_STATE_INVALID", "유효하지 않은 로그인 요청입니다."));
        validateAttempt(attempt, sessionHash, now);
    }

    private void validateAttempt(OauthLoginAttempt attempt, String sessionHash, Instant now) {
        if (!"PENDING".equals(attempt.getStatus()) || attempt.getExpiresAt().isBefore(now)) {
            throw conflict("OAUTH_STATE_INVALID", "만료되거나 이미 사용한 로그인 요청입니다.");
        }
        if (!attempt.getInitiatingSessionHash().equals(sessionHash)) {
            throw conflict("OAUTH_SESSION_MISMATCH", "로그인을 시작한 브라우저 세션과 다릅니다.");
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
                .orElseThrow(() -> conflict("OAUTH_STATE_INVALID", "유효하지 않은 로그인 요청입니다."));
        validateAttempt(attempt, sessionHash, now);
        attempt.consume(now);

        var account = socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", profile.providerUserId());
        if (account.isPresent()) {
            User user = account.get().getUser();
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "사용할 수 없는 계정입니다.");
            }
            String nickname = userProfileRepository.findByUserId(user.getId()).map(UserProfile::getNickname).orElse("회원");
            Set<String> roles = currentRoles(user);
            session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(user.getId(), nickname, roles));
            session.removeAttribute(OAUTH_NONCE);
            return "/";
        }

        String normalizedEmail = profile.email().strip().toLowerCase(Locale.ROOT);
        if (socialAccountRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
            throw conflict("SOCIAL_EMAIL_PROVIDER_CONFLICT", "이미 다른 소셜 공급자로 가입한 이메일입니다.");
        }
        session.setAttribute(PENDING_SIGNUP, new PendingSignup(
                "GOOGLE", profile.providerUserId(), profile.email(), profile.emailVerified(),
                profile.suggestedNickname(), now.plusSeconds(1800)));
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
            throw conflict("SIGNUP_CONTEXT_EXPIRED", "가입 대기 시간이 만료됐습니다.");
        }
        String normalizedNickname = normalize(nickname);
        if (userProfileRepository.existsByNormalizedNickname(normalizedNickname)) {
            throw conflict("NICKNAME_ALREADY_USED", "이미 사용 중인 닉네임입니다.");
        }
        String globalRole = initialSuperAdmin.matches(pending.provider(), pending.providerUserId())
                && !userRepository.existsByGlobalRoleAndStatus("SUPER_ADMIN", "ACTIVE")
                ? "SUPER_ADMIN"
                : "USER";
        User user = userRepository.save(User.register(globalRole, now));
        socialAccountRepository.save(SocialAccount.link(
                user, pending.provider(), pending.providerUserId(), pending.email(), pending.emailVerified(), now));
        userProfileRepository.save(UserProfile.create(user, nickname.strip(), normalizedNickname, now));
        LoginUser loginUser = new LoginUser(user.getId(), nickname.strip(), currentRoles(user));
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, loginUser);
        session.removeAttribute(PENDING_SIGNUP);
        return loginUser;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private Set<String> currentRoles(User user) {
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
            return Set.of("SUPER_ADMIN");
        }
        return membershipRepository.findByUserIdAndStatus(user.getId(), "ACTIVE")
                .map(membership -> Set.of(membership.getRole()))
                .orElseGet(Set::of);
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

    private String token() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
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
}
