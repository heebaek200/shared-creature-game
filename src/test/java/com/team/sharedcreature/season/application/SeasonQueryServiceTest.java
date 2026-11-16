
package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 현재 시즌 조회의 오류 처리와 공유 잠금 경계를 검증합니다.
 * Repository를 Mock으로 대체해 서비스 규칙만 검증합니다.
 * 시즌이 없거나 유효 기간 밖이면 409를 반환해야 합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonQueryServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-11-06T00:00:00Z");

    @Mock
    private SeasonRepository repository;

    private SeasonQueryService service;

    /**
     * 각 테스트마다 고정 Clock과 Service를 구성합니다.
     * 동일한 현재 시각을 기준으로 결과를 검증합니다.
     * DB나 시스템 타임존에 의존하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new SeasonQueryService(repository, clock);
    }

    /**
     * 유효한 ACTIVE 시즌을 정상 반환하는지 확인합니다.
     * 상태와 기간 조건이 만족된 경우를 Mock으로 준비합니다.
     * 조회 결과는 같은 시즌 엔티티여야 합니다.
     */
    @Test
    void currentSeasonReturnsWhenValid() {
        Season season = mock(Season.class);

        when(repository.findFirstByStatusOrderByStartAtDesc("ACTIVE"))
                .thenReturn(Optional.of(season));
        when(season.isActiveAt(NOW)).thenReturn(true);

        // 유효한 시즌은 그대로 반환합니다.
        assertSame(season, service.findCurrentSeason());
    }

    /**
     * ACTIVE 시즌 행이 없는 경우 409 오류를 반환합니다.
     * 이전 RESOURCE_NOT_FOUND 동작을 대체합니다.
     * 시즌 준비 중 상태를 자원 삭제와 구별합니다.
     */
    @Test
    void missingCurrentSeasonReturnsConflict() {
        when(repository.findFirstByStatusOrderByStartAtDesc("ACTIVE"))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(
                BusinessException.class,
                service::findCurrentSeason
        );

        assertEquals("CONCURRENT_MODIFICATION", error.code());
    }

    /**
     * 상태는 ACTIVE지만 시간 조건이 틀린 시즌을 거절합니다.
     * DB 상태만 검사하는 기존 문제의 회귀를 방지합니다.
     * 유효하지 않은 현재 시즌은 409로 처리합니다.
     */
    @Test
    void invalidCurrentWindowReturnsConflict() {
        Season season = mock(Season.class);

        when(repository.findFirstByStatusOrderByStartAtDesc("ACTIVE"))
                .thenReturn(Optional.of(season));
        when(season.isActiveAt(NOW)).thenReturn(false);

        BusinessException error = assertThrows(
                BusinessException.class,
                service::findCurrentSeason
        );

        assertEquals("CONCURRENT_MODIFICATION", error.code());
    }

    /**
     * 공유 잠금으로 조회한 시즌이 만료됐으면 빈 결과를 반환합니다.
     * Scheduler가 만료된 시즌을 부화 처리하지 않도록 합니다.
     * 잠금 조회 방식은 기존 Repository 메서드를 유지합니다.
     */
    @Test
    void invalidSharedSeasonReturnsEmpty() {
        Season season = mock(Season.class);

        when(repository.findForShareByStatus(
                eq("ACTIVE"), any(Pageable.class)
        )).thenReturn(List.of(season));
        when(season.isActiveAt(NOW)).thenReturn(false);

        // 만료된 시즌은 Scheduler에 전달하지 않습니다.
        assertTrue(
                service.findCurrentSeasonForShareIfPresent().isEmpty()
        );
    }

    /**
     * 공유 잠금으로 조회한 유효한 시즌을 반환합니다.
     * 기존 시즌 종료 잠금 프로토콜은 유지합니다.
     * 현재 시각에서 유효한 시즌만 사용합니다.
     */
    @Test
    void validSharedSeasonIsReturned() {
        Season season = mock(Season.class);

        when(repository.findForShareByStatus(
                eq("ACTIVE"), any(Pageable.class)
        )).thenReturn(List.of(season));
        when(season.isActiveAt(NOW)).thenReturn(true);

        assertSame(
                season,
                service.findCurrentSeasonForShareIfPresent()
                        .orElseThrow()
        );
    }
}
