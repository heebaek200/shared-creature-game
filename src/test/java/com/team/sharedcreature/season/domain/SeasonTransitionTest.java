
package com.team.sharedcreature.season.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시즌 상태 전이와 시간 경계의 유효성을 검증합니다.
 * SCHEDULED부터 ENDED까지 정상 전환과 잘못된 전환을 검사합니다.
 * 반복 실행 시 상태와 갱신 시각이 보존되는지도 확인합니다.
 */
class SeasonTransitionTest {

    private static final Instant START =
            Instant.parse("2026-11-01T15:00:00Z");

    private static final Instant END =
            Instant.parse("2026-11-29T15:00:00Z");

    private static final Instant CREATED =
            START.minusSeconds(3600);

    /**
     * 시작 시각에 초기화 완료 시즌을 활성화합니다.
     * 시작 시각은 유효 기간에 포함됩니다.
     * 상태와 수정 시각이 함께 변경되어야 합니다.
     */
    @Test
    void scheduledCanActivateAtStart() {
        Season season = season("SCHEDULED");

        // 시작 경계에서 ACTIVE로 정상 전환합니다.
        assertTrue(season.activateAfterInitialization(START));
        assertEquals("ACTIVE", season.getStatus());
        assertEquals(START, season.getUpdatedAt());
    }

    /**
     * 시즌 시작 이전에는 활성화할 수 없습니다.
     * 초기화가 끝났더라도 시작 시각을 앞당기지 않습니다.
     * 예외 이후 기존 상태가 보존되어야 합니다.
     */
    @Test
    void cannotActivateBeforeStart() {
        Season season = season("SCHEDULED");

        assertThrows(IllegalStateException.class, () ->
                season.activateAfterInitialization(
                        START.minusSeconds(1)
                )
        );

        assertEquals("SCHEDULED", season.getStatus());
    }

    /**
     * 종료 시각에 도달한 시즌을 활성화하지 않습니다.
     * 종료 경계는 유효 기간에 포함되지 않습니다.
     * 만료된 SCHEDULED 시즌은 종료 절차로 진행해야 합니다.
     */
    @Test
    void cannotActivateAtEnd() {
        Season season = season("SCHEDULED");

        assertThrows(IllegalStateException.class, () ->
                season.activateAfterInitialization(END)
        );

        assertEquals("SCHEDULED", season.getStatus());
    }

    /**
     * 이미 활성화된 시즌의 중복 전환을 무시합니다.
     * 반복 정상화로 updatedAt을 변경하면 안 됩니다.
     * 반환값은 상태 변경이 없음을 나타내야 합니다.
     */
    @Test
    void repeatedActivationDoesNotChangeTimestamp() {
        Season season = season("SCHEDULED");
        season.activateAfterInitialization(START);

        // 이미 ACTIVE인 시즌을 다시 활성화하지 않습니다.
        assertFalse(
                season.activateAfterInitialization(
                        START.plusSeconds(60)
                )
        );

        assertEquals(START, season.getUpdatedAt());
    }

    /**
     * 아직 만료되지 않은 활성 시즌은 종료하지 않습니다.
     * 종료 직전 시각까지 ACTIVE 상태가 유지되어야 합니다.
     * 중간에 종료 Snapshot을 생성하지 않도록 하는 경계입니다.
     */
    @Test
    void activeSeasonDoesNotCloseBeforeEnd() {
        Season season = season("ACTIVE");

        assertFalse(
                season.beginClosingIfExpired(
                        END.minusSeconds(1)
                )
        );

        assertEquals("ACTIVE", season.getStatus());
        assertEquals(CREATED, season.getUpdatedAt());
    }

    /**
     * 활성 시즌은 정확히 종료 시각부터 CLOSING으로 변경됩니다.
     * 종료 시각을 포함하는 조건을 검증합니다.
     * 수정 시각도 실제 종료 처리 시각으로 기록해야 합니다.
     */
    @Test
    void activeSeasonClosesAtEnd() {
        Season season = season("ACTIVE");

        // 종료 경계에 도달하면 종료 절차를 시작합니다.
        assertTrue(season.beginClosingIfExpired(END));
        assertEquals("CLOSING", season.getStatus());
        assertEquals(END, season.getUpdatedAt());
    }

    /**
     * 초기화 중 만료된 시즌도 종료를 시작할 수 있습니다.
     * SCHEDULED를 ACTIVE로 변경하지 않고 CLOSING으로 전환합니다.
     * 만료된 시즌의 소급 활성화를 방지하는 규칙입니다.
     */
    @Test
    void expiredScheduledSeasonGoesDirectlyToClosing() {
        Season season = season("SCHEDULED");

        assertTrue(
                season.beginClosingIfExpired(
                        END.plusSeconds(3600)
                )
        );

        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * CLOSING 시즌의 최종 종료를 검증합니다.
     * Snapshot 처리가 완료됐다는 전제로 호출합니다.
     * ENDED 상태와 최종 갱신 시각을 확인합니다.
     */
    @Test
    void closingSeasonCanEnd() {
        Season season = season("CLOSING");
        Instant completedAt = END.plusSeconds(120);

        // Snapshot 완료 후 최종 종료를 확정합니다.
        assertTrue(
                season.finishClosingAfterSnapshot(completedAt)
        );

        assertEquals("ENDED", season.getStatus());
        assertEquals(completedAt, season.getUpdatedAt());
    }

    /**
     * 이미 종료된 시즌에 중복 종료 요청이 와도 안전해야 합니다.
     * 종료 상태와 수정 시각이 변경되지 않아야 합니다.
     * 재기동 정상화의 반복 처리를 검증합니다.
     */
    @Test
    void repeatedClosingDoesNotChangeEndedSeason() {
        Season season = season("CLOSING");
        Instant completedAt = END.plusSeconds(120);

        season.finishClosingAfterSnapshot(completedAt);

        // 이미 ENDED이므로 수정 시각을 유지합니다.
        assertFalse(
                season.finishClosingAfterSnapshot(
                        completedAt.plusSeconds(60)
                )
        );

        assertEquals("ENDED", season.getStatus());
        assertEquals(completedAt, season.getUpdatedAt());
    }

    /**
     * SCHEDULED 시즌을 바로 ENDED로 변경하지 못하게 합니다.
     * 반드시 CLOSING 상태를 거쳐야 합니다.
     * 종료 Snapshot 절차를 건너뛰는 상태 전이를 거부합니다.
     */
    @Test
    void scheduledCannotDirectlyEnd() {
        Season season = season("SCHEDULED");

        assertThrows(IllegalStateException.class, () ->
                season.finishClosingAfterSnapshot(END)
        );

        assertEquals("SCHEDULED", season.getStatus());
    }

    /**
     * 종료된 시즌을 다시 활성화하지 못하게 합니다.
     * 과거 시즌을 복구할 때 실수로 재개하는 것을 방지합니다.
     * 새로운 시즌은 별도의 시즌 행으로 생성해야 합니다.
     */
    @Test
    void endedSeasonCannotReactivate() {
        Season season = season("ENDED");

        assertThrows(IllegalStateException.class, () ->
                season.activateAfterInitialization(
                        START.plusSeconds(3600)
                )
        );

        assertEquals("ENDED", season.getStatus());
    }

    /**
     * 테스트 전용 시즌 엔티티를 생성합니다.
     * 실제 JPA 생성자와 필드 구조를 그대로 사용합니다.
     * DB 초기화 없이 상태 전환만 검증하기 위한 Fixture입니다.
     *
     * @param status 초기 시즌 상태
     * @return 테스트 대상 시즌 엔티티
     */
    private Season season(String status) {
        Season season = new Season();

        // 기존 엔티티 구조를 변경하지 않고 테스트를 구성합니다.
        ReflectionTestUtils.setField(season, "status", status);
        ReflectionTestUtils.setField(season, "startAt", START);
        ReflectionTestUtils.setField(season, "endAt", END);
        ReflectionTestUtils.setField(season, "createdAt", CREATED);
        ReflectionTestUtils.setField(season, "updatedAt", CREATED);

        return season;
    }


    /**
     * ACTIVE 시즌의 종료 출처를 보존합니다.
     * 종료 후 랭킹 Snapshot이 필요해야 합니다.
     * 상태 변경과 출처 기록을 함께 검증합니다.
     */
    @Test
    void activeClosingRequiresSnapshot() {
        Season season = season("ACTIVE");

        assertTrue(season.beginClosingIfExpired(END));

        assertEquals("ACTIVE", season.getClosingFromStatus());
        assertTrue(season.requiresRankingSnapshotOnClosing());
    }

    /**
     * SCHEDULED 시즌의 종료 출처를 보존합니다.
     * 초기화 중 만료된 시즌은 랭킹 Snapshot 대상이 아닙니다.
     * ACTIVE 상태를 거치지 않고 종료 처리를 시작합니다.
     */
    @Test
    void scheduledClosingSkipsSnapshot() {
        Season season = season("SCHEDULED");

        assertTrue(season.beginClosingIfExpired(END));

        assertEquals("SCHEDULED", season.getClosingFromStatus());
        assertFalse(season.requiresRankingSnapshotOnClosing());
    }

    /**
     * 아직 만료되지 않은 시즌에는 종료 출처가 없어야 합니다.
     * 종료 시각 직전의 상태를 검증합니다.
     * 불필요한 상태 변경이나 이력 기록을 방지합니다.
     */
    @Test
    void unexpiredSeasonHasNoClosingOrigin() {
        Season season = season("ACTIVE");

        assertFalse(
                season.beginClosingIfExpired(END.minusSeconds(1))
        );

        assertNull(season.getClosingFromStatus());
        assertEquals("ACTIVE", season.getStatus());
    }

    /**
     * CLOSING 상태의 종료 작업을 반복해도 출처는 유지됩니다.
     * 재시작 후 정상화가 다시 호출되는 상황을 검증합니다.
     * 기존 종료 시각도 덮어쓰지 않아야 합니다.
     */
    @Test
    void repeatedClosingPreservesOrigin() {
        Season season = season("ACTIVE");

        assertTrue(season.beginClosingIfExpired(END));

        // 두 번째 실행은 상태와 출처를 변경하지 않습니다.
        assertFalse(
                season.beginClosingIfExpired(END.plusSeconds(60))
        );

        assertEquals("ACTIVE", season.getClosingFromStatus());
        assertEquals(END, season.getUpdatedAt());
    }

    /**
     * 종료 출처가 없는 CLOSING 시즌을 임의로 처리하지 않습니다.
     * 이전 버전에서 생성된 불완전한 이력을 재현합니다.
     * 알 수 없는 상태를 안전하게 예외로 처리합니다.
     */
    @Test
    void unknownClosingOriginIsRejected() {
        Season season = season("CLOSING");

        assertThrows(
                IllegalStateException.class,
                season::requiresRankingSnapshotOnClosing
        );
    }

}
