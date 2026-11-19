package com.team.sharedcreature.group.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** 단일 인스턴스에서 사용자별 채팅 성공 간격과 동시 요청을 제어합니다. */
@Component
public class GroupChatRateLimiter {

    private static final Duration INTERVAL = Duration.ofSeconds(2);

    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 서버 기준 시각으로 사용자별 제한 Window를 계산합니다. */
    public GroupChatRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 사용자 요청을 원자적으로 선점하고 제한이면 남은 지연 초와 함께 거부합니다. */
    public Permit acquire(Long userId) {
        State state = states.computeIfAbsent(userId, ignored -> new State());
        synchronized (state) {
            Instant now = clock.instant();
            Instant blockedUntil = state.inFlight
                    ? state.reservedAt.plus(INTERVAL)
                    : state.lastSuccess == null ? null : state.lastSuccess.plus(INTERVAL);
            if (state.inFlight || blockedUntil != null && now.isBefore(blockedUntil)) {
                long millis = blockedUntil == null ? 0
                        : Math.max(0, Duration.between(now, blockedUntil).toMillis());
                throw new ChatRateLimitException(Math.max(1, (millis + 999) / 1000));
            }
            state.inFlight = true;
            state.reservedAt = now;
            Permit permit = new Permit(state);
            state.activePermit = permit;
            return permit;
        }
    }

    /** DB Commit 성공 여부를 반영하고 실패한 선점은 즉시 해제합니다. */
    public void finish(Permit permit, boolean committed) {
        State state = permit.state;
        synchronized (state) {
            if (!state.inFlight || state.activePermit != permit) {
                return;
            }
            if (committed) {
                state.lastSuccess = clock.instant();
            }
            state.inFlight = false;
            state.reservedAt = null;
            state.activePermit = null;
        }
    }

    /** 한 사용자 요청의 메모리 선점 표식입니다. */
    public static final class Permit {
        private final State state;

        private Permit(State state) {
            this.state = state;
        }
    }

    private static final class State {
        private Instant lastSuccess;
        private Instant reservedAt;
        private boolean inFlight;
        private Permit activePermit;
    }
}
