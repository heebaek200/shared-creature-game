package com.team.sharedcreature.quest.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사진 퀘스트의 KST 촬영 날짜 집계 규칙을 검증합니다. */
class PhotoQuestDateTest {

    @Test
    void KST_자정_전후의_사진은_서로_다른_날짜로_계산한다() {
        List<Instant> capturedAtValues = List.of(
                Instant.parse("2026-11-05T14:59:59Z"),
                Instant.parse("2026-11-05T15:00:00Z"),
                Instant.parse("2026-11-05T15:30:00Z")
        );

        assertThat(QuestCompletionService.countDistinctKstDates(capturedAtValues))
                .isEqualTo(2);
    }

    @Test
    void 같은_KST_날짜의_사진은_여러장이어도_하루로_계산한다() {
        List<Instant> capturedAtValues = List.of(
                Instant.parse("2026-11-05T00:00:00Z"),
                Instant.parse("2026-11-05T10:00:00Z"),
                Instant.parse("2026-11-05T14:59:59Z")
        );

        assertThat(QuestCompletionService.countDistinctKstDates(capturedAtValues))
                .isEqualTo(1);
    }
}
