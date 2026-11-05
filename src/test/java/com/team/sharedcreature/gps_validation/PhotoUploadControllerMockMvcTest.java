package com.team.sharedcreature.gps_validation;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PhotoUploadControllerMockMvcTest {

    private static final int TEN_MIB = 10 * 1024 * 1024;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var handler = new RestApiExceptionHandler(new ErrorResponseFactory(Clock.systemUTC()));
        mockMvc = MockMvcBuilders.standaloneSetup(new PhotoUploadController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void 파일이_10_MiB를_초과하면_413_FILE_TOO_LARGE로_응답한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.jpg",
                "image/jpeg",
                new byte[TEN_MIB + 1]
        );

        mockMvc.perform(multipart("/api/v1/photo-submissions/upload").file(file))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void 필수_파일_Part가_없으면_400_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(multipart("/api/v1/photo-submissions/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
