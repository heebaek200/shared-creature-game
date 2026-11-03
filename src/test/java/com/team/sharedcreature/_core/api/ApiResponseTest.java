package com.team.sharedcreature._core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void success_응답은_data와_서버시각을_포함한다() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);

        ApiResponse<String> response = ApiResponse.success("ok", clock);

        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.meta().serverTime()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
    }
}
