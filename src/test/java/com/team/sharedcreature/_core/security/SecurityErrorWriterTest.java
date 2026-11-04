package com.team.sharedcreature._core.security;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorWriterTest {

    @Test
    void Security_오류도_공통_Envelope로_직렬화한다() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
        SecurityErrorWriter writer = new SecurityErrorWriter(
                new ObjectMapper(),
                new ErrorResponseFactory(clock)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("requestId", "security-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, ErrorCode.SESSION_EXPIRED);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains(
                "\"code\":\"SESSION_EXPIRED\"",
                "\"fieldErrors\":[]",
                "\"traceId\":\"security-request\"",
                "\"timestamp\":\"2026-11-03T00:00:00Z\""
        );
    }
}
