package com.team.sharedcreature.auth.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.application.AuthService;
import com.team.sharedcreature.auth.application.PendingSignup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Google OAuth와 가입 대기·현재 사용자·로그아웃 REST 흐름을 제공합니다.
 */
@RestController
@RequestMapping
public class AuthController {

    private final AuthService authService;

    /** 인증 업무 Service를 주입받습니다. */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Google Authorization URL을 생성합니다. */
    @PostMapping("/api/v1/auth/oauth-attempts")
    public ResponseEntity<ApiResponse<OAuthAttemptResponse>> createOAuthAttempt(
            @Valid @RequestBody OAuthAttemptRequest request,
            HttpSession session
    ) {
        AuthService.OAuthAttemptResult result = authService.createOAuthAttempt(
                request.provider(), request.returnRoute(), session);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                new OAuthAttemptResponse(result.authorizationUrl(), result.expiresAt())));
    }

    /** Google Callback을 검증하고 인가 코드가 없는 CSR 경로로 이동시킵니다. */
    @GetMapping("/oauth2/callback/{provider}")
    public ResponseEntity<Void> handleOAuthCallback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request
    ) {
        if (!"google".equalsIgnoreCase(provider) || error != null || code == null || state == null) {
            return redirect("/login", "OAUTH_PROVIDER_REJECTED");
        }
        try {
            String route = authService.handleCallback(state, code, request.getSession());
            request.changeSessionId();
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(route)).build();
        } catch (BusinessException exception) {
            return redirect("/login", exception.code());
        }
    }

    /** 가입 대기 세션의 검증된 공급자 정보를 반환합니다. */
    @GetMapping("/api/v1/auth/signup-context")
    public ApiResponse<SignupContextResponse> getSignupContext(HttpSession session) {
        Object attribute = session.getAttribute(AuthService.PENDING_SIGNUP);
        if (!(attribute instanceof PendingSignup pending) || pending.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SIGNUP_CONTEXT_EXPIRED", "가입 대기 시간이 만료됐습니다.");
        }
        return ApiResponse.success(new SignupContextResponse(
                pending.provider(),
                pending.email(),
                pending.suggestedNickname(),
                pending.expiresAt(),
                authService.signupInvitation(pending)
                        .map(invitation -> new SignupInvitationResponse(
                                invitation.groupId(), invitation.groupName()))
                        .orElse(null)
        ));
    }

    /** 닉네임을 확정하고 신규 내부 계정과 로그인 세션을 생성합니다. */
    @PostMapping("/api/v1/auth/signup")
    public ResponseEntity<ApiResponse<CurrentUserResponse>> completeSignup(
            @Valid @RequestBody SignupRequest signup,
            HttpServletRequest request
    ) {
        LoginUser loginUser = authService.completeSignup(signup.nickname(), request.getSession());
        request.changeSessionId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                authenticated(loginUser, request.getSession())
        ));
    }

    /** 비로그인·가입 대기·인증 사용자의 Route Guard 상태를 반환합니다. */
    @GetMapping("/api/v1/me")
    public ApiResponse<CurrentUserResponse> getCurrentUser(HttpSession session) {
        Object login = session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        if (login instanceof LoginUser loginUser) {
            return ApiResponse.success(authenticated(loginUser, session));
        }
        Object pending = session.getAttribute(AuthService.PENDING_SIGNUP);
        if (pending instanceof PendingSignup pendingSignup && pendingSignup.expiresAt().isAfter(Instant.now())) {
            return ApiResponse.success(new CurrentUserResponse(
                    "SIGNUP_PENDING", "SIGNUP_REQUIRED", null, null, List.of(), null, null));
        }
        return ApiResponse.success(new CurrentUserResponse(
                "ANONYMOUS", "NOT_STARTED", null, null, List.of(), null, null));
    }

    /** 현재 애플리케이션 세션만 무효화합니다. */
    @PostMapping("/api/v1/auth/logout")
    public ApiResponse<EmptyResponse> logout(HttpSession session) {
        session.invalidate();
        return ApiResponse.success(new EmptyResponse());
    }

    private CurrentUserResponse authenticated(LoginUser user, HttpSession session) {
        MembershipResponse membership = authService.currentMembership(user.id())
                .map(value -> new MembershipResponse(
                        value.membershipId(),
                        value.groupId(),
                        value.role(),
                        value.status(),
                        value.joinedAt()
                ))
                .orElse(null);
        String onboarding = user.roles().contains("SUPER_ADMIN") || membership != null
                ? "COMPLETED"
                : "GROUP_REQUIRED";
        String onboardingReason = authService.consumeOnboardingReason(session);
        return new CurrentUserResponse(
                "AUTHENTICATED",
                onboarding,
                new UserSummaryResponse(user.id(), user.nickname()),
                membership,
                user.roles().stream().sorted().toList(),
                authService.currentRestrictionUntil(user.id()).orElse(null),
                onboardingReason
        );
    }

    private ResponseEntity<Void> redirect(String route, String code) {
        URI location = UriComponentsBuilder.fromPath(route).queryParam("oauthError", code).build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    /** OAuth 로그인 시작 요청입니다. */
    public record OAuthAttemptRequest(
            @NotBlank @Pattern(regexp = "GOOGLE") String provider,
            @Pattern(regexp = "DEFAULT|INVITE") String returnRoute
    ) {
    }

    /** 공급자 Authorization URL 응답입니다. */
    public record OAuthAttemptResponse(String authorizationUrl, Instant expiresAt) {
    }

    /** 가입 대기 정보 응답입니다. */
    public record SignupContextResponse(
            String provider,
            String email,
            String suggestedNickname,
            Instant expiresAt,
            SignupInvitationResponse invitation
    ) {
    }

    /** 가입 화면에 필요한 최소 초대 그룹 정보입니다. */
    public record SignupInvitationResponse(Long groupId, String groupName) {
    }

    /** 신규 회원 닉네임 입력입니다. */
    public record SignupRequest(@NotBlank @Size(min = 2, max = 20) String nickname) {
    }

    /** Route Guard가 사용하는 현재 사용자 상태입니다. */
    public record CurrentUserResponse(
            String authenticationState,
            String onboardingState,
            UserSummaryResponse user,
            MembershipResponse membership,
            List<String> roles,
            Instant restrictionUntil,
            String onboardingReason
    ) {
    }

    /** 현재 사용자의 최소 표시 정보입니다. */
    public record UserSummaryResponse(Long userId, String nickname) {
    }

    /** 현재 활성 그룹 멤버십 정보입니다. */
    public record MembershipResponse(
            Long membershipId,
            Long groupId,
            String role,
            String status,
            Instant joinedAt
    ) {
    }

    /** Body가 필요한 빈 성공 응답입니다. */
    public record EmptyResponse() {
    }
}
