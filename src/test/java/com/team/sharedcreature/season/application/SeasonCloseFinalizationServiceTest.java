package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonSnapshotCompletionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Snapshot 완료 확인과 시즌 최종 종료의 상태 전이를 검증합니다.
 * 실제 Season 엔티티를 사용하여 ENDED 변경 및 갱신 시각을 검사합니다.
 * DB SQL의 정확성과 트랜잭션 Commit은 별도 MySQL 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonCloseFinalizationServiceTest {

    private static final Long SEASON_ID = 98L;
    private static final Instant END = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant FINALIZED = END.plusSeconds(180);

    @Mock
    private SeasonRepository seasonRepository;
    @Mock
    private SeasonSnapshotCompletionRepository completionRepository;

    private MutableClock clock;
    private SeasonCloseFinalizationService service;

    /**
     * 새로운 종료 Service와 테스트 Clock을 준비합니다.
     * 테스트 시각은 실제 시즌 종료 시각 이후입니다.
     * 메서드가 호출되기 전에는 트랜잭션이 시작되지 않습니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(FINALIZED);
        service = new SeasonCloseFinalizationService(
                seasonRepository, completionRepository, clock
        );
    }

    /**
     * 그룹·개인 Snapshot이 모두 완성된 ACTIVE 출처의 시즌을 종료합니다.
     * 시즌 배타 잠금이 Snapshot 검사보다 먼저 호출되어야 합니다.
     * 종료 시각은 DB 잠금 이후에 읽은 Clock 시각을 사용합니다.
     */
    @Test
    void activeClosingWithCompleteSnapshotsEnds() {
        Season season = prepareSeason("CLOSING", "ACTIVE");
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(complete());

        // 잠금 조회 후 검사하며 최종 상태를 Commit할 준비를 마칩니다.
        assertTrue(service.finishIfSnapshotsComplete(SEASON_ID));

        assertEquals("ENDED", season.getStatus());
        assertEquals(FINALIZED, season.getUpdatedAt());
        InOrder order = inOrder(seasonRepository, completionRepository);
        order.verify(seasonRepository).findForUpdateById(SEASON_ID);
        order.verify(completionRepository).inspectActiveClosing(SEASON_ID);
    }

    /**
     * 그룹 Snapshot이 한 건이라도 빠졌으면 종료할 수 없습니다.
     * 그룹 원본과 점수 행의 개수는 정상적인 상황으로 구성합니다.
     * 최종 종료를 건너뛰고 CLOSING 상태를 유지해야 합니다.
     */
    @Test
    void missingGroupSnapshotBlocksEnding() {
        Season season = prepareSeason("CLOSING", "ACTIVE");
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(
                new SeasonSnapshotCompletionRepository.Inspection(
                        2, 2, 1, 1, 0, 3, 3, 0, 0, 0
                ));

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * 개인 Snapshot이 일부 누락됐으면 종료를 거절합니다.
     * 그룹 Snapshot이 완성되어 있어도 개인 기록이 완료되어야 합니다.
     * 잔여 개인 기여도 배치를 저장한 뒤 다시 시도할 수 있습니다.
     */
    @Test
    void missingMemberSnapshotBlocksEnding() {
        Season season = prepareSeason("CLOSING", "ACTIVE");
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(
                new SeasonSnapshotCompletionRepository.Inspection(
                        2, 2, 2, 0, 0, 3, 2, 1, 0, 0
                ));

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * 건수는 같지만 순위나 점수가 다른 Snapshot을 거절합니다.
     * Snapshot 개수만 같다고 정상 종료를 확정하지 않습니다.
     * 이후 수정 없이 오류를 드러내 사람이 원인을 확인할 수 있어야 합니다.
     */
    @Test
    void incorrectRankingBlocksEnding() {
        Season season = prepareSeason("CLOSING", "ACTIVE");
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(
                new SeasonSnapshotCompletionRepository.Inspection(
                        2, 2, 2, 0, 1, 3, 3, 0, 0, 0
                ));

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * 그룹 점수 누락이 있으면 그룹 Snapshot 수와 무관하게 종료하지 않습니다.
     * 단순한 스냅샷 개수 비교에 의존하지 않기 위한 검사입니다.
     * 누락된 집계 행을 잘못된 기본값으로 생성하지 않습니다.
     */
    @Test
    void missingGroupScoreBlocksEnding() {
        Season season = prepareSeason("CLOSING", "ACTIVE");
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(
                new SeasonSnapshotCompletionRepository.Inspection(
                        2, 1, 2, 0, 0, 3, 3, 0, 0, 0
                ));

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * 유효하지 않은 원본 점수의 종료를 차단합니다.
     * 도달 시각이 없는 양수 그룹 점수와 개인 기여도를 감지한 경우입니다.
     * 오류 난 점수를 임의의 시각으로 보정하지 않습니다.
     */
    @Test
    void invalidSourceBlocksEnding() {
        Season season = prepareSeason("CLOSING", "ACTIVE");
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(
                new SeasonSnapshotCompletionRepository.Inspection(
                        2, 2, 2, 0, 0, 3, 3, 0, 0, 1
                ));

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * SCHEDULED에서 만료된 시즌은 Snapshot 없이 종료합니다.
     * 일부 그룹 시즌의 초기화 상태는 이 판정에 영향을 주지 않습니다.
     * 랭킹을 소급 생성하지 않아야 한다는 설계 규칙을 검증합니다.
     */
    @Test
    void scheduledClosingWithNoSnapshotsEnds() {
        Season season = prepareSeason("CLOSING", "SCHEDULED");
        when(completionRepository.countGroupSnapshots(SEASON_ID)).thenReturn(0L);
        when(completionRepository.countMemberSnapshots(SEASON_ID)).thenReturn(0L);

        assertTrue(service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("ENDED", season.getStatus());
        verify(completionRepository, never()).inspectActiveClosing(SEASON_ID);
    }

    /**
     * SCHEDULED 출처 시즌에 Snapshot이 있으면 종료를 거절합니다.
     * 종료 복구 중 잘못 생성된 랭킹을 조용히 승인하지 않습니다.
     * 데이터가 보존되도록 어떤 Snapshot도 삭제하지 않습니다.
     */
    @Test
    void scheduledClosingWithUnexpectedSnapshotIsRejected() {
        Season season = prepareSeason("CLOSING", "SCHEDULED");
        when(completionRepository.countGroupSnapshots(SEASON_ID)).thenReturn(1L);
        when(completionRepository.countMemberSnapshots(SEASON_ID)).thenReturn(0L);

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * 이미 ENDED인 시즌은 중복 정상화에서도 상태를 바꾸지 않습니다.
     * 검사 쿼리를 추가 실행하지 않고 그대로 반환합니다.
     * 기존 updatedAt을 변경하지 않는 멱등성 규칙입니다.
     */
    @Test
    void alreadyEndedSeasonIsIdempotent() {
        Season season = prepareSeason("ENDED", "ACTIVE");

        assertFalse(service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals(END, season.getUpdatedAt());
        verifyNoInteractions(completionRepository);
    }

    /**
     * ACTIVE 또는 SCHEDULED 시즌을 직접 ENDED로 종료할 수 없습니다.
     * 먼저 별도 트랜잭션에서 CLOSING으로 전환해야 합니다.
     * 상태가 유효하지 않은 경우 Snapshot 검사도 실행하지 않습니다.
     */
    @Test
    void activeSeasonCannotSkipClosing() {
        Season season = prepareSeason("ACTIVE", null);

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("ACTIVE", season.getStatus());
        verifyNoInteractions(completionRepository);
    }

    /**
     * 종료 출처가 없는 CLOSING 시즌은 판정을 중단합니다.
     * 과거 데이터에 ACTIVE 여부를 임의로 추정하지 않습니다.
     * 미확정 상태를 보존하여 후속 운영 복구를 가능하게 합니다.
     */
    @Test
    void unknownClosingOriginIsRejected() {
        Season season = prepareSeason("CLOSING", null);

        assertThrows(IllegalStateException.class,
                () -> service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals("CLOSING", season.getStatus());
        verifyNoInteractions(completionRepository);
    }

    /**
     * DB 배타 잠금 대기 후의 실제 시각으로 최종 종료 시각을 기록합니다.
     * Repository가 반환되기 직전 Clock을 변경하여 대기를 재현합니다.
     * 메서드 진입 전에 구한 시각을 잘못 저장하는 것을 방지합니다.
     */
    @Test
    void finalizationTimestampIsReadAfterLock() {
        Season season = createSeason("CLOSING", "ACTIVE");
        Instant afterLock = FINALIZED.plusSeconds(60);
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenAnswer(invocation -> {
            clock.set(afterLock);
            return Optional.of(season);
        });
        when(completionRepository.inspectActiveClosing(SEASON_ID)).thenReturn(complete());

        assertTrue(service.finishIfSnapshotsComplete(SEASON_ID));
        assertEquals(afterLock, season.getUpdatedAt());
    }

    /**
     * 그룹 및 개인 원본이 모두 확정됐고 Snapshot과 일치하는 결과입니다.
     * 이전 배치에서 모든 Snapshot이 Commit된 상황을 모의합니다.
     * 랭킹·점수·원본 행에 불일치가 없는 값으로 구성합니다.
     */
    private SeasonSnapshotCompletionRepository.Inspection complete() {
        return new SeasonSnapshotCompletionRepository.Inspection(
                2, 2, 2, 0, 0, 3, 3, 0, 0, 0
        );
    }

    /**
     * protected JPA 생성자를 리플렉션으로 호출해 시즌을 준비합니다.
     * CLOSING 출처 필드와 updatedAt을 실제 도메인 객체에 주입합니다.
     * 각 테스트에서 배타 잠금 결과로 반환하도록 Repository를 설정합니다.
     */
    private Season prepareSeason(String status, String closingFromStatus) {
        Season season = createSeason(status, closingFromStatus);
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));
        return season;
    }

    /**
     * 실제 엔티티를 리플렉션으로 생성하고 종료 상태를 주입합니다.
     * Repository Stub 없이 객체만 구성하여 잠금 응답 시각 테스트에도 사용합니다.
     * 패키지가 다른 protected JPA 생성자를 직접 호출하지 않습니다.
     */
    private Season createSeason(String status, String closingFromStatus) {
        Season season = BeanUtils.instantiateClass(Season.class);

        // 실제 엔티티에 값을 채워 검증 시 도메인 메서드를 실행하게 합니다.
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        ReflectionTestUtils.setField(season, "status", status);
        ReflectionTestUtils.setField(season, "closingFromStatus", closingFromStatus);
        ReflectionTestUtils.setField(season, "updatedAt", END);
        return season;
    }

    /**
     * 트랜잭션 경계에서 실제 시각의 이동을 흉내내는 테스트 Clock입니다.
     * DB를 사용하지 않고 조회 대기 전후의 시각 차이를 재현합니다.
     * 시스템의 실제 시간이나 테스트 실행 속도에 의존하지 않습니다.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        /**
         * 조회 대기 이후에 관측되는 테스트 시각을 변경합니다.
         * 테스트별 객체에서만 변경되므로 다른 테스트에 영향을 주지 않습니다.
         * 바뀐 값은 다음 Clock.instant() 호출부터 반영됩니다.
         */
        private void set(Instant next) {
            this.now = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
