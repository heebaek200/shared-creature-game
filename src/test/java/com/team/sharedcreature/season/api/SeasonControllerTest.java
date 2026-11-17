package com.team.sharedcreature.season.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature.season.application.SeasonQueryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 랭킹 선택용 시즌 목록 Controller가 공통 성공 Envelope을 유지하는지 검증합니다. */
class SeasonControllerTest {

    @Test
    void 랭킹선택용_시즌목록을_공통응답으로_반환한다() {
        SeasonQueryService service = mock(SeasonQueryService.class);
        SeasonController controller = new SeasonController(service);
        List<SeasonResponse> seasons = List.of(new SeasonResponse(
                2L,
                2,
                "ACTIVE",
                Instant.parse("2026-11-02T15:00:00Z"),
                Instant.parse("2026-11-30T15:00:00Z")
        ));
        when(service.listRankableSeasons()).thenReturn(seasons);

        ApiResponse<List<SeasonResponse>> response = controller.listRankableSeasons();

        assertEquals(seasons, response.data());
        verify(service).listRankableSeasons();
    }
}
