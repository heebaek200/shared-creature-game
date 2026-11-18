package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature.admin.application.AdminModerationService;
import com.team.sharedcreature.admin.application.AdminReportQueryService;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.domain.UserReportStatus;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminReportStatusHttpTest {

    private static final String REPORTS_PATH = "/api/v1/admin/reports";
    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 20);

    private UserReportRepository reports;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reports = mock(UserReportRepository.class);
        var queries = new AdminReportQueryService(reports, mock(UserProfileRepository.class));
        var controller = new AdminReportController(queries, mock(AdminModerationService.class));
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        var methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();
        var validatedController = methodValidation.postProcessAfterInitialization(controller, "adminReportController");
        mvc = MockMvcBuilders.standaloneSetup(validatedController)
                .setControllerAdvice(new RestApiExceptionHandler(new ErrorResponseFactory(Clock.systemUTC())))
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.destroy();
    }

    @ParameterizedTest
    @EnumSource(UserReportStatus.class)
    void queriesByDocumentedStatus(UserReportStatus filter) throws Exception {
        when(reports.findByStatusOrderByCreatedAtDescIdDesc(filter, DEFAULT_PAGE))
                .thenReturn(Page.empty(DEFAULT_PAGE));

        mvc.perform(get(REPORTS_PATH).param("status", filter.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(reports).findByStatusOrderByCreatedAtDescIdDesc(filter, DEFAULT_PAGE);
    }

    @Test
    void queriesAllReportsWhenStatusIsOmitted() throws Exception {
        when(reports.findAllByOrderByCreatedAtDescIdDesc(DEFAULT_PAGE)).thenReturn(Page.empty(DEFAULT_PAGE));

        mvc.perform(get(REPORTS_PATH)).andExpect(status().isOk());

        verify(reports).findAllByOrderByCreatedAtDescIdDesc(DEFAULT_PAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"received", "PENDING", "", " RECEIVED "})
    void rejectsInvalidStatusWithExistingFieldError(String filter) throws Exception {
        mvc.perform(get(REPORTS_PATH).param("status", filter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));

        verifyNoInteractions(reports);
    }
}
