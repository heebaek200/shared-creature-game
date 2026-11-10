package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.web.StrictQueryParameterInterceptor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestApiExceptionHandlerMockMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var handler = new RestApiExceptionHandler(new ErrorResponseFactory(Clock.systemUTC()));
        JsonMapper objectMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(handler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .addInterceptors(new StrictQueryParameterInterceptor())
                .build();
    }

    @Test
    void Bean_Validation은_설계된_필드_오류로_응답한다() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[0].reason").doesNotExist());
    }

    @Test
    void Path_타입_오류는_400_INVALID_FIELD_VALUE로_응답한다() throws Exception {
        mockMvc.perform(get("/test/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
    }

    @Test
    void 필수_Query_누락은_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(get("/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 지원하지_않는_Content_Type은_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 잘못된_JSON은_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 미등록_JSON_필드는_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid\",\"unknown\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 중복_Query는_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(get("/required?q=first&q=second"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 미등록_Query는_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(get("/required?q=value&unknown=value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 선언된_Query는_정상적으로_Controller에_전달한다() throws Exception {
        mockMvc.perform(get("/required?q=value"))
                .andExpect(status().isOk());
    }

    @Test
    void Query_제약조건_위반은_필드_오류로_응답한다() throws Exception {
        mockMvc.perform(get("/bounded?size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void 업무_충돌은_정의된_409_오류로_응답한다() throws Exception {
        mockMvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_FULL"));
    }

    @Test
    void Controller_반환값_검증_실패는_500으로_응답한다() throws Exception {
        mockMvc.perform(get("/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @RestController
    static class TestController {

        @PostMapping("/test")
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/test/{id}")
        void path(@PathVariable Long id) {
        }

        @GetMapping("/required")
        void required(@RequestParam String q) {
        }

        @GetMapping("/bounded")
        void bounded(@RequestParam @Min(1) int size) {
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }

        @GetMapping("/invalid-return")
        @NotNull
        String invalidReturn() {
            return null;
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
