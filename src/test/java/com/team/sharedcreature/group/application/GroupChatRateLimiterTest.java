package com.team.sharedcreature.group.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupChatRateLimiterTest {

    @Test
    void 진행_중인_요청은_2초가_지나도_동시_선점을_허용하지_않는다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        GroupChatRateLimiter limiter = new GroupChatRateLimiter(clock);
        GroupChatRateLimiter.Permit permit = limiter.acquire(1L);
        clock.instant = clock.instant.plusSeconds(3);

        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOfSatisfying(ChatRateLimitException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(1));

        limiter.finish(permit, false);
        assertThat(limiter.acquire(1L)).isNotNull();
    }

    @Test
    void 성공한_요청부터_2초_동안_올림한_Retry_After를_반환한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        GroupChatRateLimiter limiter = new GroupChatRateLimiter(clock);
        limiter.finish(limiter.acquire(1L), true);
        clock.instant = clock.instant.plusMillis(1001);

        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOfSatisfying(ChatRateLimitException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(1));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
