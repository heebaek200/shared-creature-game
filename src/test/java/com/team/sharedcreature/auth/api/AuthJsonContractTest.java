package com.team.sharedcreature.auth.api;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.application.AuthService;
import com.team.sharedcreature.auth.application.PendingSignup;
import com.team.sharedcreature.group.domain.GroupMembershipStatus;
import com.team.sharedcreature.group.domain.GroupRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthJsonContractTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuthController controller = new AuthController(authService);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MockHttpSession session = new MockHttpSession();

    @Test
    void anonymousUserReceivesNotStarted() {
        assertStates(currentUserData(), "ANONYMOUS", "NOT_STARTED");
    }

    @Test
    void pendingUserReceivesSignupRequired() {
        session.setAttribute(AuthService.PENDING_SIGNUP, new PendingSignup(
                "google", "123", "user@example.com", true, "creature", null, Instant.MAX));

        assertStates(currentUserData(), "SIGNUP_PENDING", "SIGNUP_REQUIRED");
    }

    @Test
    void authenticatedUserWithoutMembershipReceivesGroupRequired() {
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "creature", Set.of()));

        assertStates(currentUserData(), "AUTHENTICATED", "GROUP_REQUIRED");
    }

    @Test
    void authenticatedUserWithMembershipReceivesCompleted() {
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "creature", Set.of()));
        when(authService.currentMembership(1L)).thenReturn(Optional.of(new AuthService.CurrentMembership(
                1L, 2L, GroupRole.DEPUTY, GroupMembershipStatus.ACTIVE, Instant.EPOCH)));

        var data = currentUserData();
        assertStates(data, "AUTHENTICATED", "COMPLETED");
        assertMembership(data.get("membership"), "DEPUTY");
    }

    @Test
    void signupResponseSerializesMembershipRoleAndStatusAsStrings() {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        when(authService.completeSignup("creature", session))
                .thenReturn(new LoginUser(1L, "creature", Set.of("MEMBER")));
        when(authService.currentMembership(1L)).thenReturn(Optional.of(new AuthService.CurrentMembership(
                1L, 2L, GroupRole.MEMBER, GroupMembershipStatus.ACTIVE, Instant.EPOCH)));

        var response = controller.completeSignup(new AuthController.SignupRequest("creature"), request);
        var membership = mapper.readTree(mapper.writeValueAsString(response.getBody())).at("/data/membership");

        assertMembership(membership, "MEMBER");
    }

    private JsonNode currentUserData() {
        return mapper.readTree(mapper.writeValueAsString(controller.getCurrentUser(session))).get("data");
    }

    private void assertStates(JsonNode data, String authenticationState, String onboardingState) {
        assertThat(data.get("authenticationState").isString()).isTrue();
        assertThat(data.get("authenticationState").asString()).isEqualTo(authenticationState);
        assertThat(data.get("onboardingState").isString()).isTrue();
        assertThat(data.get("onboardingState").asString()).isEqualTo(onboardingState);
    }

    private void assertMembership(JsonNode membership, String role) {
        assertThat(membership.get("role").isString()).isTrue();
        assertThat(membership.get("role").asString()).isEqualTo(role);
        assertThat(membership.get("status").isString()).isTrue();
        assertThat(membership.get("status").asString()).isEqualTo("ACTIVE");
    }
}
