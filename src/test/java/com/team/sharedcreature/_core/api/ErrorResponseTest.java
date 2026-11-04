package com.team.sharedcreature._core.api;

import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseTest {

    @Test
    void 필드_오류는_message_키로_직렬화한다() throws Exception {
        ErrorResponse response = new ErrorResponseFactory(
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
        ).create(
                ErrorCode.INVALID_FIELD_VALUE,
                null,
                List.of(new FieldErrorResponse("nickname", "2자 이상이어야 합니다.")),
                "request-1"
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"field\":\"nickname\"");
        assertThat(json).contains("\"message\":\"2자 이상이어야 합니다.\"");
        assertThat(json).doesNotContain("reason");
    }
}
