package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestApiExceptionHandlerMockMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var handler = new RestApiExceptionHandler(new ErrorResponseFactory(Clock.systemUTC()));
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(handler)
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
    }

    record TestRequest(@NotBlank String name) {
    }
}
