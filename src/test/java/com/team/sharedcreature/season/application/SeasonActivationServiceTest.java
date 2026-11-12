package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonActivationInspectionRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SCHEDULED 시즌 전체 초기화 검사와 ACTIVE 전환의 경계 조건을 검증합니다.
 * 실제 Season 도메인 객체를 이용해 상태·시각 불변성과 멱등성을 테스트합니다.
 * DB 잠금·집계 SQL은 별도 MySQL 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonActivationServiceTest {

    private static final Long SEASON_ID = 71L;
    private static final Instant START = Instant.parse("2027-01-24T15:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(28));
    private static final Instant NOW = START.plus(Duration.ofDays(5));

    @Mock private SeasonRepository seasonRepository;
    @Mock private SeasonActivationInspectionRepository inspectionRepository;

    private MutableClock clock;
    private SeasonActivationService service;

    /**
     * 모든 테스트에 독립된 변경 가능 UTC Clock과 Mock Repository를 준비합니다.
     * 각 테스트가 임의로 시간을 변경해도 다른 테스트 결과에 영향을 주지 않습니다.
     * 실제 DB나 Scheduler 자동 실행을 필요로 하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new SeasonActivationService(seasonRepository, inspectionRepository, clock);
    }

    /**
     * 전체 활성 그룹이 준비된 경우 정확히 한 번 ACTIVE로 전환합니다.
     * 검증이 끝난 뒤 상태가 바뀌고 updatedAt이 계산 시각으로 저장됩니다.
     * 시즌 잠금 조회가 전체 검사보다 먼저 호출돼야 합니다.
     */
    @Test
    void completeGroupsActivateSeason() {
        Season season = scheduledSeason();
        when(inspectionRepository.inspect(SEASON_ID)).thenReturn(complete(3));

        var result = service.activateIfInitializationComplete(SEASON_ID);

        assertEquals(SeasonActivationService.Outcome.ACTIVATED, result.outcome());
        assertEquals("ACTIVE", season.getStatus());
        assertEquals(NOW, season.getUpdatedAt());
        assertEquals(3, result.inspection().activeGroups());
        var order = inOrder(seasonRepository, inspectionRepository);
        order.verify(seasonRepository).findForUpdateById(SEASON_ID);
        order.verify(inspectionRepository).inspect(SEASON_ID);
    }

    /**
     * 활성 그룹이 없는 경우 추가 그룹 초기화는 필요하지 않습니다.
     * 0개 그룹은 빈 컬렉션처럼 정상적인 완료 상태로 취급합니다.
     * 그룹이 없다는 사실만으로 시즌 전환을 막지 않습니다.
     */
    @Test
    void noActiveGroupsStillAllowsActivation() {
        Season season = scheduledSeason();
        when(inspectionRepository.inspect(SEASON_ID)).thenReturn(complete(0));

        assertEquals(SeasonActivationService.Outcome.ACTIVATED,
                service.activateIfInitializationComplete(SEASON_ID).outcome());
        assertEquals("ACTIVE", season.getStatus());
    }

    /**
     * GroupSeason 행 자체가 없는 활성 그룹이 있으면 기다립니다.
     * 누락된 그룹의 알·점수를 가짜 기본값으로 만들지 않습니다.
     * 다음 초기화 배치가 Commit될 때까지 SCHEDULED를 유지합니다.
     */
    @Test
    void missingGroupSeasonBlocksActivation() {
        assertIncomplete(new SeasonActivationInspectionRepository.Inspection(
                4, 1, 0, 0, 0, 0, 0));
    }

    /**
     * 그룹 시즌이 존재하더라도 알이 빠져 있으면 활성화하지 않습니다.
     * 그룹 초기화 배치가 누락된 알을 복구한 뒤 다시 검사할 수 있습니다.
     * 완료 검사는 GroupSeason 수만으로 판정하지 않습니다.
     */
    @Test
    void missingEggBlocksActivation() {
        assertIncomplete(new SeasonActivationInspectionRepository.Inspection(
                4, 0, 1, 0, 0, 0, 0));
    }

    /**
     * 모든 그룹의 0점 집계가 있어야 시즌을 활성화할 수 있습니다.
     * 누락된 점수 행은 초기화 Service가 재실행할 때 복구합니다.
     * ACTIVE 전환 이후의 퀘스트 보상 정합성을 보호합니다.
     */
    @Test
    void missingScoreBlocksActivation() {
        assertIncomplete(new SeasonActivationInspectionRepository.Inspection(
                4, 0, 0, 1, 0, 0, 0));
    }

    /**
     * PREPARING이 아닌 그룹 시즌이나 잘못된 72시간 기한을 거절합니다.
     * 기존 GroupSeason을 임의로 다시 만들지 않습니다.
     * 초기화 완료가 아니라 운영상 정합성 문제로 판단합니다.
     */
    @Test
    void invalidGroupSeasonBlocksActivation() {
        assertIncomplete(new SeasonActivationInspectionRepository.Inspection(
                4, 0, 0, 0, 1, 0, 0));
    }

    /**
     * SCHEDULED 시즌인데 이미 부화한 크리처가 있으면 활성화하지 않습니다.
     * 부화 상태를 EGG로 되돌리거나 종 선택을 삭제하지 않습니다.
     * 원인을 조사할 수 있도록 시즌은 SCHEDULED로 유지합니다.
     */
    @Test
    void invalidEggBlocksActivation() {
        assertIncomplete(new SeasonActivationInspectionRepository.Inspection(
                4, 0, 0, 0, 0, 1, 0));
    }

    /**
     * SCHEDULED 시즌의 그룹 점수가 0점이 아니면 활성화하지 않습니다.
     * 이전 시즌 점수가 새 시즌으로 이월되는 잘못된 상태를 방지합니다.
     * 점수와 도달 시각은 검사만 하며 직접 덮어쓰지 않습니다.
     */
    @Test
    void invalidScoreBlocksActivation() {
        assertIncomplete(new SeasonActivationInspectionRepository.Inspection(
                4, 0, 0, 0, 0, 0, 1));
    }

    /**
     * 이미 ACTIVE인 시즌을 두 번째로 활성화하지 않습니다.
     * 기존 updatedAt 및 GroupSeason·알 상태를 변경하지 않습니다.
     * DB 집계 조회도 실행하지 않는 멱등성 조건을 검증합니다.
     */
    @Test
    void alreadyActiveIsIdempotent() {
        Season season = scheduledSeason();
        ReflectionTestUtils.setField(season, "status", "ACTIVE");

        var result = service.activateIfInitializationComplete(SEASON_ID);

        assertEquals(SeasonActivationService.Outcome.ALREADY_ACTIVE, result.outcome());
        assertEquals(NOW, season.getUpdatedAt());
        verifyNoInteractions(inspectionRepository);
    }

    /**
     * 시작 전 SCHEDULED 시즌은 전체 초기화 검사와 활성화를 건너뜁니다.
     * 실제 현재 시각이 startAt보다 이전이면 생성된 행을 이용해 선행 활성화하지 않습니다.
     * 재점검 주기에서 다시 시도할 수 있는 결과를 반환합니다.
     */
    @Test
    void beforeStartDoesNotActivate() {
        Season season = scheduledSeason();
        clock.set(START.minusSeconds(1));

        assertEquals(SeasonActivationService.Outcome.NOT_STARTED,
                service.activateIfInitializationComplete(SEASON_ID).outcome());
        assertEquals("SCHEDULED", season.getStatus());
        verifyNoInteractions(inspectionRepository);
    }

    /**
     * 종료 경계에 정확히 도달한 시즌은 더 이상 ACTIVE로 바꾸지 않습니다.
     * 현재 구간을 이미 지나간 경우에는 CLOSING 정상화로 넘겨야 합니다.
     * 알의 72시간 대기 시간을 억지로 단축하지 않습니다.
     */
    @Test
    void atEndDoesNotActivate() {
        Season season = scheduledSeason();
        clock.set(END);

        assertEquals(SeasonActivationService.Outcome.EXPIRED,
                service.activateIfInitializationComplete(SEASON_ID).outcome());
        assertEquals("SCHEDULED", season.getStatus());
        verifyNoInteractions(inspectionRepository);
    }

    /**
     * ENDED 및 CLOSING 상태는 SCHEDULED로 되돌리지 않습니다.
     * 종료 중인 시즌의 Snapshot과 종료 시각은 재생성하지 않습니다.
     * 다른 정상화 Service가 해당 상태를 전담해야 합니다.
     */
    @Test
    void endingSeasonDoesNotActivate() {
        Season season = scheduledSeason();
        ReflectionTestUtils.setField(season, "status", "CLOSING");

        assertEquals(SeasonActivationService.Outcome.NOT_SCHEDULED,
                service.activateIfInitializationComplete(SEASON_ID).outcome());
        assertEquals("CLOSING", season.getStatus());
        verifyNoInteractions(inspectionRepository);
    }

    /**
     * 전체 그룹 검사가 끝나기 전에 종료 경계에 도달할 수 있습니다.
     * 검사가 완전하더라도 검사 종료 시각이 endAt이면 활성화하면 안 됩니다.
     * Clock을 DB 조회 도중 전진시켜 해당 경합을 재현합니다.
     */
    @Test
    void expiryDuringInspectionDoesNotActivate() {
        Season season = scheduledSeason();
        when(inspectionRepository.inspect(SEASON_ID)).thenAnswer(invocation -> {
            clock.set(END);
            return complete(2);
        });

        assertEquals(SeasonActivationService.Outcome.EXPIRED,
                service.activateIfInitializationComplete(SEASON_ID).outcome());
        assertEquals("SCHEDULED", season.getStatus());
    }

    /**
     * 시즌 식별자가 잘못됐다면 다른 시즌으로 대체해서는 안 됩니다.
     * 조회 실패를 명확한 예외로 드러냅니다.
     * 활성 그룹 검사와 상태 전환은 실행하지 않습니다.
     */
    @Test
    void missingSeasonThrows() {
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.activateIfInitializationComplete(SEASON_ID));
        verifyNoInteractions(inspectionRepository);
    }

    /**
     * 누락이나 비정상 기록이 존재할 때 상태가 유지되는 공통 검증입니다.
     * 검사 결과를 호출자에게 그대로 반환해 운영 경고에 활용할 수 있습니다.
     * 오류 상태라도 서비스가 임의의 복구 INSERT를 수행하지 않습니다.
     */
    private void assertIncomplete(SeasonActivationInspectionRepository.Inspection inspection) {
        Season season = scheduledSeason();
        when(inspectionRepository.inspect(SEASON_ID)).thenReturn(inspection);

        var result = service.activateIfInitializationComplete(SEASON_ID);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertSame(inspection, result.inspection());
        assertFalse(inspection.complete());
        assertEquals("SCHEDULED", season.getStatus());
        assertEquals(NOW, season.getUpdatedAt());
    }

    /**
     * 모든 그룹의 초기화 결과가 완전한 조회 응답을 생성합니다.
     * 활성 그룹이 0개인 상태도 정상으로 처리합니다.
     * 누락·비정상 항목 수는 전부 0으로 설정합니다.
     */
    private SeasonActivationInspectionRepository.Inspection complete(long activeGroups) {
        return new SeasonActivationInspectionRepository.Inspection(
                activeGroups, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 도메인 팩토리로 실제 SCHEDULED 시즌 엔티티를 생성합니다.
     * 테스트용 식별자만 리플렉션으로 설정하고 기존 상태 전이 메서드를 실행합니다.
     * Repository의 비관적 잠금 반환값으로 사용합니다.
     */
    private Season scheduledSeason() {
        Season season = Season.schedule(2, START, END, NOW);
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));
        return season;
    }

    /**
     * 잠금·검사 대기 중 시간 경계를 넘는 상황을 모의하는 Clock입니다.
     * 시스템 전역 Clock을 바꾸지 않고 각 테스트 인스턴스에만 적용합니다.
     * ZoneOffset.UTC를 사용하여 시스템 타임존과 무관하게 검증합니다.
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        /**
         * 다음 instant() 호출에서 사용할 테스트 시각을 변경합니다.
         * 잠금 대기 또는 집계 쿼리 도중의 시간 이동을 재현합니다.
         * Thread.sleep에 의존하지 않고 재현 가능한 테스트를 제공합니다.
         */
        private void set(Instant now) {
            this.now = now;
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
