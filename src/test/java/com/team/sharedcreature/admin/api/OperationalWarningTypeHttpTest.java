package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature.admin.application.OperationalWarningService;
import com.team.sharedcreature.admin.domain.OperationalWarningType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
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

class OperationalWarningTypeHttpTest {

    private static final String WARNINGS_PATH = "/api/v1/admin/operations/warnings";

    private OperationalWarningService warnings;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        warnings = mock(OperationalWarningService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        var methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();
        var controller = methodValidation.postProcessAfterInitialization(
                new AdminOperationsController(warnings), "adminOperationsController");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptionHandler(new ErrorResponseFactory(Clock.systemUTC())))
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.destroy();
    }

    @ParameterizedTest
    @EnumSource(OperationalWarningType.class)
    void passesDocumentedWarningTypeToService(OperationalWarningType filter) throws Exception {
        when(warnings.list(filter, 0, 20)).thenReturn(Page.empty());

        mvc.perform(get(WARNINGS_PATH).param("type", filter.name())).andExpect(status().isOk());

        verify(warnings).list(filter, 0, 20);
    }

    @Test
    void queriesAllWarningTypesWhenFilterIsOmitted() throws Exception {
        when(warnings.list(null, 0, 20)).thenReturn(Page.empty());

        mvc.perform(get(WARNINGS_PATH)).andExpect(status().isOk());

        verify(warnings).list(null, 0, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo_deletion_failed", " PHOTO_DELETION_FAILED", "", "UNKNOWN"})
    void rejectsInvalidWarningTypeWithExistingFieldError(String filter) throws Exception {
        mvc.perform(get(WARNINGS_PATH).param("type", filter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("type"));

        verifyNoInteractions(warnings);
    }
}
