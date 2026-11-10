package com.team.sharedcreature.quest.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXIF Worker 동시 실행 수와 Timeout 처리를 검증합니다.
 */
class PhotoProcessingLimiterTest {

    private PhotoProcessingLimiter limiter;
    private ExecutorService callers;

    @BeforeEach
    void setUp() {
        limiter = new PhotoProcessingLimiter();
        callers = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        callers.shutdownNow();
        limiter.shutdown();
    }

    @Test
    void 네_개의_Worker를_점유하면_다섯번째_예약은_RATE_LIMIT_EXCEEDED로_거절한다()
            throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        List<Future<String>> tasks = new ArrayList<>();

        // 네 작업을 동시에 실행해 모든 Worker 자리를 점유합니다.
        for (int i = 0; i < 4; i++) {
            tasks.add(callers.submit(() -> {
                try (PhotoProcessingLimiter.Permit permit = limiter.reserve()) {
                    return limiter.execute(permit, () -> {
                        workersStarted.countDown();
                        releaseWorkers.await();
                        return "done";
                    });
                }
            }));
        }

        assertThat(workersStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // 네 자리가 모두 예약된 동안 추가 요청은 바로 거절되어야 합니다.
        assertThatThrownBy(limiter::reserve)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));

        // 작업을 풀어 모든 Future가 정상 완료되도록 합니다.
        releaseWorkers.countDown();

        for (Future<String> task : tasks) {
            assertThat(task.get(2, TimeUnit.SECONDS)).isEqualTo("done");
        }

        // 작업 종료 뒤 Worker 자리가 반환됐는지 확인합니다.
        try (PhotoProcessingLimiter.Permit permit = limiter.reserve()) {
            assertThat(permit).isNotNull();
        }
    }

    @Test
    void 사용하지_않은_예약은_close하면_반환된다() {
        PhotoProcessingLimiter.Permit first = limiter.reserve();
        first.close();

        // 반환된 자리를 다시 예약할 수 있어야 합니다.
        try (PhotoProcessingLimiter.Permit second = limiter.reserve()) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    void 작업이_5초를_넘으면_TimeoutException이_발생하고_Worker_자리는_반환된다() {
        CountDownLatch workerStarted = new CountDownLatch(1);

        assertThatThrownBy(() -> {
            try (PhotoProcessingLimiter.Permit permit = limiter.reserve()) {
                limiter.execute(permit, () -> {
                    workerStarted.countDown();

                    // Limiter의 Timeout 취소 신호가 올 때까지 대기합니다.
                    new CountDownLatch(1).await();
                    return "unreachable";
                });
            }
        }).isInstanceOf(TimeoutException.class);

        assertThat(workerStarted.getCount()).isZero();

        // 취소된 Worker가 종료한 뒤 예약 자리가 반환되는지 확인합니다.
        PhotoProcessingLimiter.Permit recoveredPermit = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (recoveredPermit == null && System.nanoTime() < deadline) {
            try {
                recoveredPermit = limiter.reserve();
            } catch (BusinessException exception) {
                Thread.yield();
            }
        }

        assertThat(recoveredPermit).isNotNull();
        recoveredPermit.close();
    }
}