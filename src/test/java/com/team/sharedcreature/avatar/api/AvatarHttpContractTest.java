package com.team.sharedcreature.avatar.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.avatar.application.AvatarCommandRetryService;
import com.team.sharedcreature.avatar.application.AvatarService;
import com.team.sharedcreature.avatar.application.ShopQueryService;
import com.team.sharedcreature.avatar.support.AvatarHttpTestConfiguration;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.user;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 세션 인증·CSRF·MVC·두 예외 처리기의 적용 범위를 HTTP 요청으로 검증합니다. */
@SpringJUnitConfig(AvatarHttpTestConfiguration.class)
@WebAppConfiguration
class AvatarHttpContractTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private GroupMembershipRepository memberships;
    @Autowired private AvatarService avatarService;
    @Autowired private AvatarCommandRetryService commands;
    @Autowired private ShopQueryService shopService;
    @Autowired private ObjectMapper mapper;

    private MockMvc mvc;
    private final AvatarResponse emptyAvatar = new AvatarResponse(
            1L, new AvatarResponse.Equipments(null, null, null, null));

    @BeforeEach
    void setUp() {
        reset(users, memberships, avatarService, commands, shopService);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/shop-items", "/api/v1/owned-items", "/api/v1/avatar"})
    void 로그인_세션이_없으면_조회가_거부된다(String path) throws Exception {
        mvc.perform(get(path).header("X-Request-Id", "issue20-anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.traceId").value("issue20-anonymous"));
        verifyNoInteractions(avatarService, commands, shopService);
    }

    @Test
    void 만료된_세션의_조회는_SESSION_EXPIRED를_반환한다() throws Exception {
        mvc.perform(get("/api/v1/avatar").with(request -> {
                    request.setRequestedSessionId("expired-issue20-session");
                    request.setRequestedSessionIdValid(false);
                    return request;
                }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 장착과_해제는_CSRF_토큰_없이는_실행하지_않는다() throws Exception {
        var session = authenticatedSession();
        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(session)
                        .contentType("application/json").content("{\"ownedItemId\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(delete("/api/v1/avatar/equipments/HEAD").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(commands);
    }

    @Test
    void 실제_발급한_CSRF_헤더와_세션으로_최초_장착을_호출한다() throws Exception {
        var session = authenticatedSession();
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        var token = mapper.readTree(csrfResponse.getContentAsString()).get("data");
        when(commands.equipAvatarItem(1L, "HEAD", 10L, true)).thenReturn(emptyAvatar);

        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(session)
                        .header(token.get("headerName").asText(), token.get("token").asText())
                        .contentType("application/json")
                        .content("{\"ownedItemId\":10,\"confirmFirstUse\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1));

        verify(commands).equipAvatarItem(1L, "HEAD", 10L, true);
    }

    @Test
    void 동의를_생략한_요청은_false로_Service에_전달한다() throws Exception {
        when(commands.equipAvatarItem(1L, "HEAD", 10L, false)).thenReturn(emptyAvatar);

        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(authenticatedSession())
                        .with(csrf().asHeader()).contentType("application/json")
                        .content("{\"ownedItemId\":10}"))
                .andExpect(status().isOk());

        verify(commands).equipAvatarItem(1L, "HEAD", 10L, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ownedItemId\":\"10\"}",
            "{\"ownedItemId\":10.0}",
            "{\"ownedItemId\":10.8}",
            "{\"ownedItemId\":9223372036854775808}",
            "{\"ownedItemId\":true}",
            "{\"ownedItemId\":{}}",
            "{\"ownedItemId\":10,\"confirmFirstUse\":\"true\"}",
            "{\"ownedItemId\":10,\"confirmFirstUse\":1}",
            "{\"ownedItemId\":10,\"confirmFirstUse\":[]}"
    })
    void 장착_요청의_잘못된_JSON_타입은_필드_오류로_거부한다(String body) throws Exception {
        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(authenticatedSession())
                        .with(csrf().asHeader()).header("X-Request-Id", "issue20-invalid-type")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.traceId").value("issue20-invalid-type"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ownedItemId\":null}", "{\"ownedItemId\":0}", "{\"ownedItemId\":-1}"})
    void 필수_ID_누락과_범위_위반은_검증_오류다(String body) throws Exception {
        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(authenticatedSession())
                        .with(csrf().asHeader()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "{\"ownedItemId\":}",
            "{\"ownedItemId\":10,\"confirmFirstUse\":null}", "{\"ownedItemId\":10,\"userId\":2}"})
    void 잘못된_JSON_형식과_미등록_필드는_요청_오류다(String body) throws Exception {
        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(authenticatedSession())
                        .with(csrf().asHeader()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/shop-items", "/api/v1/owned-items"})
    void 두_목록의_중복과_미등록_Query만_필드_오류로_변환한다(String path) throws Exception {
        var session = authenticatedSession();
        mvc.perform(get(path).session(session).queryParam("page", "0", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
        mvc.perform(get(path).session(session).queryParam("unknown", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
        verifyNoInteractions(avatarService, shopService);
    }

    @Test
    void 목록_기본값과_페이지_메타데이터를_유지한다() throws Exception {
        when(shopService.list(0, 20, null)).thenReturn(Page.empty(PageRequest.of(0, 20)));
        when(avatarService.listOwnedItems(1L, 0, 20, null, null))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));
        var session = authenticatedSession();

        for (String path : new String[]{"/api/v1/shop-items", "/api/v1/owned-items"}) {
            mvc.perform(get(path).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.meta.page").value(0))
                    .andExpect(jsonPath("$.meta.size").value(20))
                    .andExpect(jsonPath("$.meta.totalElements").value(0));
        }
    }

    @Test
    void 최초_사용_동의_누락_업무_오류는_409를_유지한다() throws Exception {
        when(commands.equipAvatarItem(1L, "HEAD", 10L, false))
                .thenThrow(new BusinessException(ErrorCode.FIRST_USE_CONFIRMATION_REQUIRED));

        mvc.perform(put("/api/v1/avatar/equipments/HEAD").session(authenticatedSession())
                        .with(csrf().asHeader()).contentType("application/json").content("{\"ownedItemId\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_USE_CONFIRMATION_REQUIRED"));
    }

    @Test
    void 다른_Controller의_Query와_JSON_동작은_기존대로_유지한다() throws Exception {
        var session = authenticatedSession();
        mvc.perform(get("/api/v1/issue20-test/other").session(session).queryParam("unknown", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // 이슈 20의 엄격한 타입 검사가 다른 DTO의 변환 방식까지 바꾸면 안 됩니다.
        mvc.perform(post("/api/v1/issue20-test/other").session(session).with(csrf().asHeader())
                        .contentType("application/json").content("{\"id\":\"10\",\"enabled\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/v1/issue20-test/other").session(session).with(csrf().asHeader())
                        .contentType("application/json").content("{\"id\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** 인증 필터가 실제로 복원하는 로그인 세션을 준비합니다. */
    private MockHttpSession authenticatedSession() {
        when(users.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(memberships.findByUserIdAndStatus(1L, "ACTIVE")).thenReturn(Optional.empty());
        var session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "테스트 회원", Set.of()));
        return session;
    }
}
