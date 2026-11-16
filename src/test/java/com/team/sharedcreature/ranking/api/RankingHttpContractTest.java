package com.team.sharedcreature.ranking.api;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.*;
import com.team.sharedcreature._core.security.*;
import com.team.sharedcreature._core.web.*;
import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.ranking.application.RankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 MVC·세션 인증·Query 검증을 통해 랭킹 API 계약을 검증합니다. */
@SpringJUnitConfig(RankingHttpContractTest.Configuration.class)
@WebAppConfiguration
class RankingHttpContractTest {

    @Autowired private WebApplicationContext context;
    @Autowired private RankingService service;
    @Autowired private UserRepository users;
    @Autowired private GroupMembershipRepository memberships;
    @Autowired private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, users, memberships);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"members", "groups"})
    void 로그인없이_조회하면_인증오류와_추적ID를_반환한다(String kind) throws Exception {
        mvc.perform(get("/api/v1/rankings/" + kind).header("X-Request-Id", "ranking-anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.traceId").value("ranking-anonymous"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"members", "groups"})
    void 만료된_세션은_SESSION_EXPIRED를_반환한다(String kind) throws Exception {
        mvc.perform(get("/api/v1/rankings/" + kind).with(request -> {
                    request.setRequestedSessionId("expired-ranking-session");
                    request.setRequestedSessionIdValid(false);
                    return request;
                }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIMARY", "DEPUTY", "MEMBER"})
    void 그룹구성원은_기본값으로_개인랭킹을_조회한다(String role) throws Exception {
        when(service.getMemberRanking(1L, null, 0, 20)).thenReturn(emptyPage());
        mvc.perform(get("/api/v1/rankings/members").session(session(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20));
        verify(service).getMemberRanking(1L, null, 0, 20);
    }

    @Test
    void 그룹미가입자는_개인랭킹을_조회할수없다() throws Exception {
        mvc.perform(get("/api/v1/rankings/members").session(session(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test
    void 그룹미가입자도_그룹랭킹을_조회하며_null키는_생략하지_않는다() throws Exception {
        when(service.getGroupRanking(1L, null, 0, 20)).thenReturn(emptyPage());
        var result = mvc.perform(get("/api/v1/rankings/groups").session(session(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0))
                .andReturn();
        var meta = mapper.readTree(result.getResponse().getContentAsString()).get("meta");
        assertThat(meta.has("currentSubject")).isTrue();
        assertThat(meta.get("currentSubject").isNull()).isTrue();
    }

    @Test
    void 명시한_Query와_세션사용자ID를_Service에_전달한다() throws Exception {
        when(service.getMemberRanking(1L, Long.MAX_VALUE, 2, 50))
                .thenReturn(new RankingResponse.PageDto<>(List.of(),
                        new RankingResponse.Meta(2, 50, 0, 0, null)));
        mvc.perform(get("/api/v1/rankings/members").session(session("MEMBER"))
                        .queryParam("seasonId", Long.toString(Long.MAX_VALUE))
                        .queryParam("page", "2").queryParam("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.size").value(50));
        verify(service).getMemberRanking(1L, Long.MAX_VALUE, 2, 50);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,1.5", "page,abc", "page,2147483648",
            "size,0", "size,51", "size,true", "seasonId,0", "seasonId,-1",
            "seasonId,1.0", "seasonId,9223372036854775808"})
    void 잘못된_Query값은_두경로에서_필드오류로_거절한다(String key, String value) throws Exception {
        for (String kind : List.of("members", "groups")) {
            mvc.perform(get("/api/v1/rankings/" + kind).session(session("MEMBER"))
                            .queryParam(key, value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "seasonId"})
    void 빈_Query값에_기본값을_적용하지_않는다(String key) throws Exception {
        for (String kind : List.of("members", "groups")) {
            mvc.perform(get("/api/v1/rankings/" + kind).session(session("MEMBER"))
                            .queryParam(key, ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0&page=1", "size=20&size=20", "seasonId=1&seasonId=2",
            "unknown=1", "userId=2", "page=0&%70age=1"})
    void 중복과_미등록_Query는_랭킹에서만_필드오류로_변환한다(String query) throws Exception {
        for (String kind : List.of("members", "groups")) {
            mvc.perform(get(URI.create("/api/v1/rankings/" + kind + "?" + query))
                            .session(session("MEMBER")).header("X-Request-Id", "ranking-query"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                    .andExpect(jsonPath("$.traceId").value("ranking-query"))
                    .andExpect(jsonPath("$.fieldErrors").isEmpty());
        }
        verifyNoInteractions(service);
    }

    @Test
    void 다른_Controller의_Query오류는_기존코드를_유지한다() throws Exception {
        mvc.perform(get("/api/v1/ranking-test/other").session(session(null))
                        .queryParam("unknown", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void ContextPath가_있어도_랭킹_Query오류를_변환한다() throws Exception {
        mvc.perform(get("/game/api/v1/rankings/groups").contextPath("/game")
                        .servletPath("/api/v1/rankings/groups")
                        .session(session(null)).queryParam("unknown", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
    }

    @Test
    void 개인응답에_참여상태와_페이지밖_현재순위와_UTC시각을_포함한다() throws Exception {
        when(service.getMemberRanking(1L, null, 0, 20)).thenReturn(
                new RankingResponse.PageDto<>(List.of(new RankingResponse.MemberDto(
                        1, 2L, "탈퇴회원", 100L, Instant.parse("2026-11-12T00:00:00Z"), "LEFT")),
                        new RankingResponse.Meta(0, 20, 25, 2,
                                new RankingResponse.CurrentSubject(1L, 23, 5L))));
        mvc.perform(get("/api/v1/rankings/members").session(session("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(2))
                .andExpect(jsonPath("$.data[0].participantStatus").value("LEFT"))
                .andExpect(jsonPath("$.data[0].scoreReachedAt").value("2026-11-12T00:00:00Z"))
                .andExpect(jsonPath("$.meta.currentSubject.subjectId").value(1))
                .andExpect(jsonPath("$.meta.currentSubject.rank").value(23))
                .andExpect(jsonPath("$.meta.currentSubject.score").value(5));
    }

    @Test
    void 그룹응답의_필드와_순위없음_null키를_유지한다() throws Exception {
        when(service.getGroupRanking(1L, 10L, 1, 5)).thenReturn(
                new RankingResponse.PageDto<>(List.of(new RankingResponse.GroupDto(
                        6, 7L, "그룹이름", 30L, Instant.parse("2026-11-12T00:00:00Z"))),
                        new RankingResponse.Meta(1, 5, 6, 2,
                                new RankingResponse.CurrentSubject(9L, null, 0L))));
        var result = mvc.perform(get("/api/v1/rankings/groups").session(session(null))
                        .queryParam("seasonId", "10").queryParam("page", "1").queryParam("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(6))
                .andExpect(jsonPath("$.data[0].groupId").value(7))
                .andExpect(jsonPath("$.data[0].groupName").value("그룹이름"))
                .andExpect(jsonPath("$.data[0].score").value(30))
                .andReturn();
        var subject = mapper.readTree(result.getResponse().getContentAsString())
                .get("meta").get("currentSubject");
        assertThat(subject.has("rank")).isTrue();
        assertThat(subject.get("rank").isNull()).isTrue();
        verify(service).getGroupRanking(1L, 10L, 1, 5);
    }

    @ParameterizedTest
    @CsvSource({"ROLE_FORBIDDEN,403", "CONCURRENT_MODIFICATION,409", "INTERNAL_SERVER_ERROR,500"})
    void 업무오류의_상태와_코드를_변경하지_않는다(String name, int httpStatus) throws Exception {
        when(service.getMemberRanking(1L, null, 0, 20))
                .thenThrow(new BusinessException(ErrorCode.valueOf(name)));
        mvc.perform(get("/api/v1/rankings/members").session(session("MEMBER")))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(name));
    }

    /** 실제 인증 필터가 계정과 최신 멤버십 역할을 확인하도록 세션을 구성합니다. */
    private MockHttpSession session(String role) {
        User user = User.register("USER", Instant.now());
        ReflectionTestUtils.setField(user, "id", 1L);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        if (role == null) {
            when(memberships.findByUserIdAndStatus(1L, "ACTIVE")).thenReturn(Optional.empty());
        } else {
            var membership = BeanUtils.instantiateClass(GroupMembership.class);
            ReflectionTestUtils.setField(membership, "role", role);
            when(memberships.findByUserIdAndStatus(1L, "ACTIVE"))
                    .thenReturn(Optional.of(membership));
        }
        var session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "조회회원", Set.of()));
        return session;
    }

    private <T> RankingResponse.PageDto<T> emptyPage() {
        return new RankingResponse.PageDto<>(List.of(), new RankingResponse.Meta(0, 20, 0, 0, null));
    }

    /** DB와 업무 Service만 대체하며 운영 보안·MVC·예외 처리기를 그대로 연결합니다. */
    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, WebConfiguration.class, RequestIdFilter.class,
            StrictQueryParameterInterceptor.class, SessionAuthenticationFilter.class,
            SecurityErrorWriter.class, ErrorResponseFactory.class, RestApiExceptionHandler.class,
            RankingApiExceptionHandler.class, RankingController.class, IsolationController.class})
    static class Configuration {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JsonMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean RankingService rankingService() { return mock(RankingService.class); }
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean GroupMembershipRepository groupMembershipRepository() { return mock(GroupMembershipRepository.class); }
        @Bean UserActivityService userActivityService() { return mock(UserActivityService.class); }
    }

    /** 랭킹 전용 예외 변환이 다른 Controller로 확산되지 않는지 확인할 대조군입니다. */
    @RestController
    static class IsolationController {
        @GetMapping("/api/v1/ranking-test/other")
        public Map<String, Boolean> other() { return Map.of("ok", true); }
    }
}
