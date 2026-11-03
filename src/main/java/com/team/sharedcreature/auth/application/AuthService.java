package com.team.sharedcreature.auth.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.domain.OauthLoginAttempt;
import com.team.sharedcreature.auth.domain.SocialAccount;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.OauthLoginAttemptRepository;
import com.team.sharedcreature.auth.infrastructure.SocialAccountRepository;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
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
            Clock clock
    ) {
        this.attemptRepository = attemptRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.membershipRepository = membershipRepository;
        this.socialLoginClient = socialLoginClient;
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
    @Transactional
    public String handleCallback(String state, String code, HttpSession session) {
        Instant now = Instant.now(clock);
        OauthLoginAttempt attempt = attemptRepository.findByStateHash(Hashing.sha256(state))
                .orElseThrow(() -> conflict("OAUTH_STATE_INVALID", "유효하지 않은 로그인 요청입니다."));
        if (!"PENDING".equals(attempt.getStatus()) || attempt.getExpiresAt().isBefore(now)) {
            throw conflict("OAUTH_STATE_INVALID", "만료되거나 이미 사용한 로그인 요청입니다.");
        }
        if (!attempt.getInitiatingSessionHash().equals(Hashing.sha256(session.getId()))) {
            throw conflict("OAUTH_SESSION_MISMATCH", "로그인을 시작한 브라우저 세션과 다릅니다.");
        }
        String nonce = (String) session.getAttribute(OAUTH_NONCE);
        SocialLoginClient.SocialProfile profile = socialLoginClient.exchange(code, nonce);
        attempt.consume(now);

        var account = socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", profile.providerUserId());
        if (account.isPresent()) {
            User user = account.get().getUser();
            String nickname = userProfileRepository.findByUserId(user.getId()).map(UserProfile::getNickname).orElse("회원");
            Set<String> roles = currentRoles(user);
            session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(user.getId(), nickname, roles));
            session.removeAttribute(OAUTH_NONCE);
            return "/creature";
        }

        String normalizedEmail = profile.email().strip().toLowerCase(Locale.ROOT);
        if (socialAccountRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
            throw conflict("SOCIAL_EMAIL_PROVIDER_CONFLICT", "이미 다른 소셜 공급자로 가입한 이메일입니다.");
        }
        session.setAttribute(PENDING_SIGNUP, new PendingSignup(
                "GOOGLE", profile.providerUserId(), profile.email(), profile.emailVerified(),
                profile.suggestedNickname(), now.plusSeconds(1800)));
        session.removeAttribute(OAUTH_NONCE);
        return "/signup";
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
        User user = userRepository.save(User.register("USER", now));
        socialAccountRepository.save(SocialAccount.link(
                user, pending.provider(), pending.providerUserId(), pending.email(), pending.emailVerified(), now));
        userProfileRepository.save(UserProfile.create(user, nickname.strip(), normalizedNickname, now));
        LoginUser loginUser = new LoginUser(user.getId(), nickname.strip(), Set.of("USER"));
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
                .orElseGet(() -> Set.of("USER"));
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
}
