package com.team.sharedcreature.payment.api;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.payment.application.PaymentReconciliationService;
import com.team.sharedcreature.payment.application.PurchaseQueryService;
import com.team.sharedcreature.payment.application.PurchaseService;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.time.Clock;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PurchaseStatusHttpTest {

    private static final String PURCHASES_PATH = "/api/v1/purchases";
    private PurchaseQueryService queries;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = mock(PurchaseQueryService.class);
        var controller = new PaymentController(
                mock(PurchaseService.class), queries, mock(PaymentReconciliationService.class));
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();

        var login = new LoginUser(7L, "member", Set.of("MEMBER"));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(login, null));
        mvc = MockMvcBuilders.standaloneSetup(
                        methodValidation.postProcessAfterInitialization(controller, "paymentController"))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new RestApiExceptionHandler(new ErrorResponseFactory(Clock.systemUTC())))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        validator.destroy();
    }

    @ParameterizedTest
    @EnumSource(PurchaseStatus.class)
    void queriesByDocumentedStatus(PurchaseStatus filter) throws Exception {
        when(queries.list(7L, 0, 20, filter)).thenReturn(Page.empty());

        mvc.perform(get(PURCHASES_PATH).param("status", filter.name())).andExpect(status().isOk());

        verify(queries).list(7L, 0, 20, filter);
    }

    @Test
    void queriesAllPurchasesWhenStatusIsOmitted() throws Exception {
        when(queries.list(7L, 0, 20, null)).thenReturn(Page.empty());

        mvc.perform(get(PURCHASES_PATH)).andExpect(status().isOk());

        verify(queries).list(7L, 0, 20, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", " PAID", "PAID ", "UNKNOWN", "CANCEL_PENDING", "", " ", "\t"})
    void rejectsInvalidStatusWithExistingFieldError(String filter) throws Exception {
        mvc.perform(get(PURCHASES_PATH).param("status", filter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));

        verifyNoInteractions(queries);
    }
}
