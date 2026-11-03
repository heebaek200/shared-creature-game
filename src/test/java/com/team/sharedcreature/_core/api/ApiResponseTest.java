package com.team.sharedcreature._core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

class ApiResponseTest {

    @Test
    void success_응답은_data만_직렬화한다() throws Exception {
        ApiResponse<String> response = ApiResponse.success("ok");
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.meta()).isNull();
        assertThat(json).isEqualTo("{\"data\":\"ok\"}");
    }

    @Test
    void page_응답은_설계된_네_가지_페이지_메타만_제공한다() throws Exception {
        var page = new PageImpl<>(List.of("item"), PageRequest.of(1, 20), 21);

        String json = new ObjectMapper().writeValueAsString(ApiResponse.page(page));

        assertThat(json).contains("\"page\":1");
        assertThat(json).contains("\"size\":20");
        assertThat(json).contains("\"totalElements\":21");
        assertThat(json).contains("\"totalPages\":2");
        assertThat(json).doesNotContain("serverTime");
    }
}
