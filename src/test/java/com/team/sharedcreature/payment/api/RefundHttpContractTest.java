package com.team.sharedcreature.payment.api;

import com.team.sharedcreature.group.domain.GroupMembershipStatus;

import com.team.sharedcreature.auth.domain.GlobalRole;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RefundApiExceptionHandler;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.*;
import com.team.sharedcreature._core.web.*;
import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.payment.application.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** 실제 보안·MVC·예외 처리기를 사용하고 DB 및 PG 업무 경계만 대체합니다. */
@SpringJUnitConfig(RefundHttpContractTest.ConfigurationForTest.class)
@WebAppConfiguration
class RefundHttpContractTest {
    @Autowired private WebApplicationContext context;
    @Autowired private RefundService refunds;
    @Autowired private RefundQueryService queries;
    @Autowired private RefundReconciliationService reconciliation;
    @Autowired private UserRepository users;
    @Autowired private GroupMembershipRepository memberships;
    @Autowired private UserActivityService activities;
    private MockMvc mvc;
    private final String key = UUID.randomUUID().toString();

    @BeforeEach
    void 준비() {
        reset(refunds, queries, reconciliation, users, memberships, activities);
        when(users.findById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            var user = User.register(id == 9 ? GlobalRole.SUPER_ADMIN : GlobalRole.USER, Instant.now());
            ReflectionTestUtils.setField(user, "id", id);
            return Optional.of(user);
        });
        when(memberships.findByUserIdAndStatus(anyLong(), eq(GroupMembershipStatus.ACTIVE))).thenReturn(Optional.empty());
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession session(long id) {
        var session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(id, "테스트", Set.of()));
        return session;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/refund-requests", "/api/v1/admin/refund-requests"})
    void 세션이_없으면_인증_오류를_반환한다(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(refunds, queries, reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/refund-requests", "/api/v1/admin/refund-requests/1"})
    void 일반사용자는_관리자조회에_접근할수없다(String path) throws Exception {
        mvc.perform(get(path).session(session(1))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        verifyNoInteractions(queries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/purchases/1/refund-requests", "/api/v1/admin/refund-requests/1/reconciliations"})
    void 상태변경은_CSRF없이_실행할수없다(String path) throws Exception {
        mvc.perform(post(path).session(session(9)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"환불\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(refunds, reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=1&page=2", "?unknown=x", "?page=", "?size=51", "?status=WRONG"})
    void 중복_미등록_잘못된_목록Query는_400이다(String query) throws Exception {
        // 업무 Service도 상태를 검사하므로 이 사례의 잘못된 상태만 동일하게 재현합니다.
        if (query.contains("WRONG")) {
            when(queries.listMyRefunds(anyLong(), anyInt(), anyInt(), eq("WRONG")))
                    .thenThrow(new com.team.sharedcreature._core.exception.BusinessException(ErrorCode.INVALID_FIELD_VALUE));
        }
        mvc.perform(get("/api/v1/refund-requests" + query).session(session(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "123", "[]", "{}", "null", "\"   \""})
    void 사유의_잘못된_타입과_빈값을_거절한다(String reason) throws Exception {
        mvc.perform(post("/api/v1/purchases/1/refund-requests").session(session(1)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":" + reason + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
        verifyNoInteractions(refunds);
    }

    @Test
    void 미등록_JSON필드는_거절한다() throws Exception {
        mvc.perform(post("/api/v1/purchases/1/refund-requests").session(session(1)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"환불\",\"extra\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    @Test
    void 중복_JSON키는_거절한다() throws Exception {
        mvc.perform(post("/api/v1/purchases/1/refund-requests").session(session(1)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"첫번째\",\"reason\":\"두번째\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approvals", "rejections"})
    void 승인과_거절의_중복_판단사유를_거절한다(String action) throws Exception {
        mvc.perform(post("/api/v1/admin/refund-requests/1/" + action)
                        .session(session(9)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionReason\":\"첫번째\",\"decisionReason\":\"두번째\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reason", "decisionReason"})
    void 첫값이_null이어도_중복키를_거절한다(String field) throws Exception {
        String path = "reason".equals(field) ? "/api/v1/purchases/1/refund-requests"
                : "/api/v1/admin/refund-requests/1/approvals";
        mvc.perform(post(path).session(session(9)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + field + "\":null,\"" + field + "\":\"다음 사유\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    @Test
    void 생성성공은_201과_null키를_포함한_공통응답이다() throws Exception {
        var response = new RefundResponse(1L, 2L, 1L, "환불", null, "REQUESTED", Instant.EPOCH, null, null);
        when(refunds.createRefundRequest(1L, 2L, "환불", key)).thenReturn(response);
        mvc.perform(post("/api/v1/purchases/2/refund-requests").session(session(1)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  환불  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.refundRequestId").value(1))
                .andExpect(jsonPath("$.data.decisionReason").value(nullValue()))
                .andExpect(jsonPath("$.data.reviewedAt").value(nullValue()));
        verify(refunds).createRefundRequest(1L, 2L, "환불", key);
    }

    @Test
    void 빈목록도_data배열과_meta를_반환한다() throws Exception {
        when(queries.listMyRefunds(1L, 0, 20, null)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0, 20)));
        mvc.perform(get("/api/v1/refund-requests").session(session(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(0)).andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    void 수동복구_결과불명은_409오류Envelope으로_반환한다() throws Exception {
        var error = new ErrorResponseFactory(Clock.systemUTC()).create(ErrorCode.PG_CANCEL_RESULT_UNKNOWN, null, List.of(), "first");
        when(reconciliation.reconcileRefund(eq(9L), eq(1L), eq(key), anyString()))
                .thenReturn(new RefundApprovalTransactionService.CommandResponse(409, null, error));
        mvc.perform(post("/api/v1/admin/refund-requests/1/reconciliations").session(session(9))
                        .with(csrf().asHeader()).header("Idempotency-Key", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PG_CANCEL_RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.traceId").value("first")).andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 운영 보안·MVC 설정을 사용하며 테스트 전용 업무 대역을 등록합니다. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, WebConfiguration.class,
            RefundController.class, AdminRefundController.class,
            RefundApiExceptionHandler.class, RestApiExceptionHandler.class,
            StrictQueryParameterInterceptor.class, SessionAuthenticationFilter.class,
            RequestIdFilter.class, SecurityErrorWriter.class, ErrorResponseFactory.class})
    static class ConfigurationForTest implements WebMvcConfigurer {
        @Bean static MethodValidationPostProcessor methodValidationPostProcessor() {
            var processor = new MethodValidationPostProcessor();
            processor.setProxyTargetClass(true);
            return processor;
        }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JsonMapper mapper() {
            // 운영 설정과 동일하게 미등록 JSON 필드를 거절합니다.
            return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new JacksonJsonHttpMessageConverter(mapper()));
        }
        @Bean RefundService refunds() { return mock(RefundService.class); }
        @Bean RefundQueryService queries() { return mock(RefundQueryService.class); }
        @Bean RefundReconciliationService reconciliation() { return mock(RefundReconciliationService.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean GroupMembershipRepository memberships() { return mock(GroupMembershipRepository.class); }
        @Bean UserActivityService activities() { return mock(UserActivityService.class); }
    }
}
