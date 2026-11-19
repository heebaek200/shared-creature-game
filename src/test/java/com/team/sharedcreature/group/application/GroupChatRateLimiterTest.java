package com.team.sharedcreature.group.application;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

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

    @Test
    void 실패_종료와_동시에_대기한_요청들이_경쟁해도_동기화_경계가_분리되지_않는다()
            throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        GroupChatRateLimiter limiter = new GroupChatRateLimiter(clock);
        GroupChatRateLimiter.Permit first = limiter.acquire(1L);
        Object state = ReflectionTestUtils.getField(first, "state");
        assertThat(state).isNotNull();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        Thread waiting = new Thread(() -> compete(limiter, succeeded, limited), "chat-waiting");
        Thread newcomer = new Thread(() -> compete(limiter, succeeded, limited), "chat-newcomer");

        synchronized (state) {
            waiting.start();
            awaitBlocked(waiting);
            limiter.finish(first, false);
            newcomer.start();
        }
        waiting.join(2000);
        newcomer.join(2000);

        assertThat(waiting.isAlive()).isFalse();
        assertThat(newcomer.isAlive()).isFalse();
        assertThat(succeeded).hasValue(1);
        assertThat(limited).hasValue(1);
    }

    @Test
    void 이전_Permit의_중복_종료는_다음_선점을_해제하거나_성공으로_기록하지_않는다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        GroupChatRateLimiter limiter = new GroupChatRateLimiter(clock);
        GroupChatRateLimiter.Permit first = limiter.acquire(1L);
        limiter.finish(first, false);
        GroupChatRateLimiter.Permit second = limiter.acquire(1L);

        limiter.finish(first, true);

        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOf(ChatRateLimitException.class);
        limiter.finish(second, false);
        assertThat(limiter.acquire(1L)).isNotNull();
    }

    @Test
    void 서로_다른_사용자는_각자_독립적으로_선점한다() {
        GroupChatRateLimiter limiter = new GroupChatRateLimiter(
                new MutableClock(Instant.parse("2026-11-19T00:00:00Z")));

        assertThat(limiter.acquire(1L)).isNotNull();
        assertThat(limiter.acquire(2L)).isNotNull();
    }

    private void compete(
            GroupChatRateLimiter limiter,
            AtomicInteger succeeded,
            AtomicInteger limited
    ) {
        try {
            limiter.acquire(1L);
            succeeded.incrementAndGet();
        } catch (ChatRateLimitException exception) {
            limited.incrementAndGet();
        }
    }

    private void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        if (thread.getState() != Thread.State.BLOCKED) {
            fail("대기 요청이 기존 사용자 State의 Monitor에서 차단되지 않았습니다.");
        }
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
