package com.team.sharedcreature.auth.application;

import com.team.sharedcreature.auth.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserActivityServiceTest {

    /**
     * 인증 활동 기록의 DB 갱신 기준이 현재 시각보다 10분 전인지 검증합니다.
     *
     * 서버 시계를 고정하여 실행 환경과 관계없이 동일한 기준을 사용합니다.
     * Repository에는 갱신 대상 사용자와 현재 시각 및 기준 시각을 전달해야 합니다.
     */
    @Test
    void 인증_활동_기록은_10분_전_시각을_조건으로_사용한다() {
        Instant now = Instant.parse("2026-11-04T05:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        // 고정 시각을 사용하도록 Service를 생성합니다.
        UserRepository repository = mock(UserRepository.class);
        UserActivityService service = new UserActivityService(repository, clock);

        when(repository.updateLastActiveAtIfDue(
                1L,
                now,
                now.minusSeconds(600)
        )).thenReturn(1);

        // 활동 기록을 수행하고 실제 전달된 조건을 확인합니다.
        int updated = service.recordSuccessfulRequest(1L);

        assertThat(updated).isEqualTo(1);

        verify(repository).updateLastActiveAtIfDue(
                1L,
                now,
                now.minusSeconds(600)
        );
    }
}