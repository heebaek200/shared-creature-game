package com.team.sharedcreature._core.security;

import com.team.sharedcreature._core.web.RequestIdFilter;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 설계된 공개·인증·관리자 경로와 세션·CSRF 정책을 적용합니다.
 */
@Configuration
public class SecurityConfiguration {

    /**
     * REST 경로 등급과 인증·인가 실패 응답을 구성합니다.
     *
     * @param http Spring Security 설정 대상
     * @param sessionAuthenticationFilter 세션 인증 복원 Filter
     * @param requestIdFilter 요청 추적 Filter
     * @param errorWriter 공통 보안 오류 Writer
     * @return 구성된 보안 Filter Chain
     * @throws Exception 보안 구성 실패
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionAuthenticationFilter sessionAuthenticationFilter,
            RequestIdFilter requestIdFilter,
            SecurityErrorWriter errorWriter
    ) throws Exception {
        var csrfRepository = new HttpSessionCsrfTokenRepository();
        csrfRepository.setHeaderName("X-CSRF-TOKEN");

        http
                .cors(cors -> { })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .ignoringRequestMatchers("/oauth2/callback/**"))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/me").permitAll()
                        .requestMatchers("/api/v1/auth/oauth-attempts", "/api/v1/auth/signup-context", "/api/v1/auth/signup").permitAll()
                        .requestMatchers("/oauth2/callback/**").permitAll()
                        .requestMatchers("/api/v1/invitations/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/creature-species")
                        .hasAnyRole("PRIMARY", "DEPUTY")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/groups/me/seasons/current/creature-selection")
                        .hasAnyRole("PRIMARY", "DEPUTY")
                        .requestMatchers(
                                "/api/v1/groups/me/members/*/role",
                                "/api/v1/groups/me/primary-transfer",
                                "/api/v1/groups/me/invitation/**"
                        ).hasRole("PRIMARY")
                        .requestMatchers("/api/v1/groups/me/members/*/removals").hasAnyRole("PRIMARY", "DEPUTY")
                        .requestMatchers(
                                "/api/v1/groups/me/**",
                                "/api/v1/quests/**",
                                "/api/v1/quest-assignments/**",
                                "/api/v1/check-ins/**",
                                "/api/v1/photo-submissions/**",
                                "/api/v1/rankings/members/**"
                        ).hasAnyRole("PRIMARY", "DEPUTY", "MEMBER")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        Boolean.TRUE.equals(request.getAttribute(SessionAuthenticationFilter.SESSION_EXPIRED))
                                                ? ErrorCode.SESSION_EXPIRED
                                                : ErrorCode.AUTH_REQUIRED
                                ))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                errorWriter.write(request, response, ErrorCode.CSRF_INVALID);
                                return;
                            }
                            errorWriter.write(request, response, ErrorCode.ROLE_FORBIDDEN);
                        }))
                .addFilterBefore(requestIdFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(sessionAuthenticationFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Vite 개발 서버의 Cookie 포함 요청만 로컬 CORS로 허용합니다.
     *
     * @return CORS 설정 원본
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type", "X-CSRF-TOKEN", "X-Request-Id", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
