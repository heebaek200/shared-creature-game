package com.team.sharedcreature._core.security;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeMembershipRefreshesSessionRoleFromDatabase() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        User user = mock(User.class);
        GroupMembership membership = mock(GroupMembership.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getGlobalRole()).thenReturn("USER");
        when(membershipRepository.findByUserIdAndStatus(1L, "ACTIVE")).thenReturn(Optional.of(membership));
        when(membership.getRole()).thenReturn("PRIMARY");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "새싹지기", Set.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new SessionAuthenticationFilter(userRepository, membershipRepository)
                .doFilter(request, response, chain);

        LoginUser refreshed = (LoginUser) session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        assertThat(refreshed.roles()).containsExactly("PRIMARY");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_PRIMARY");
        verify(chain).doFilter(request, response);
    }

    @Test
    void inactiveAccountRemovesAuthenticatedSessionState() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn("SUSPENDED");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "새싹지기", Set.of("PRIMARY")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        new SessionAuthenticationFilter(userRepository, membershipRepository)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(session.getAttribute(SessionAuthenticationFilter.LOGIN_USER)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
