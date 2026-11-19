package com.team.sharedcreature.ai.application;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Component
public class RequestRateLimiter {

    private static final int MAX_PER_MINUTE = 5;
    private static final int MAX_PER_DAY = 30;
    private static final int MAX_CONCURRENT = 2;

    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final ConcurrentHashMap<Long, UserUsage> userUsages = new ConcurrentHashMap<>();

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT);

    public enum LimitResult {
        ACQUIRED,
        PER_MINUTE_LIMIT,
        DAILY_LIMIT,
        CONCURRENT_LIMIT
    }

    private static class UserUsage {
        private final Deque<Instant> requestTimes = new ArrayDeque<>();
        private LocalDate requestDate = LocalDate.now(KOREA_ZONE);
        private int dailyCount = 0;
    }

    public LimitResult acquire(Long userId) {
        UserUsage usage = userUsages.computeIfAbsent(userId, id -> new UserUsage());

        synchronized (usage) {
            Instant now = Instant.now();
            LocalDate today = LocalDate.now(KOREA_ZONE);

            if (!today.equals(usage.requestDate)) {
                usage.requestDate = today;
                usage.dailyCount = 0;
            }

            Instant oneMinuteAgo = now.minusSeconds(60);

            while (!usage.requestTimes.isEmpty()
                    &&!usage.requestTimes.peekFirst().isAfter(oneMinuteAgo)) {
                usage.requestTimes.removeFirst();
            }

            if (usage.dailyCount >= MAX_PER_DAY) {
                return LimitResult.DAILY_LIMIT;
            }

            if (usage.requestTimes.size() >= MAX_PER_MINUTE) {
                return LimitResult.PER_MINUTE_LIMIT;
            }

            if (!semaphore.tryAcquire()) {
                return LimitResult.CONCURRENT_LIMIT;
            }

            usage.requestTimes.addLast(now);
            usage.dailyCount++;

            return LimitResult.ACQUIRED;
        }
    }

    public void release() {
        semaphore.release();
    }

}
