package com.team.sharedcreature.quest.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EXIF 판정 Worker 수와 작업 제한 시간을 관리합니다.
 *
 * Worker가 모두 사용 중일 때는 대기열에 쌓지 않고 즉시 포화 상태를 반환합니다.
 * 제출 이력 생성 전에 이 Limiter를 통해 Worker를 확보해야 포화 요청이 시도 횟수에 포함되지 않습니다.
 */
@Component
public class PhotoProcessingLimiter {

    /** EXIF 파싱 작업의 최대 동시 실행 수 */
    private static final int MAX_CONCURRENT_TASKS = 4;

    /** EXIF 파싱 작업의 최대 허용 시간 */
    private static final long TASK_TIMEOUT_SECONDS = 5;

    /** 대기 작업을 두지 않아 Worker 포화 시 즉시 거절합니다. */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_TASKS,
            MAX_CONCURRENT_TASKS,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            workerThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    /** 제출 이력 생성 전에 EXIF 처리 Worker 자리를 예약합니다. */
    private final Semaphore availablePermits =
            new Semaphore(MAX_CONCURRENT_TASKS, true);

    /**
     * PENDING 이력을 저장하기 전에 사용할 Worker 자리를 확보합니다.
     *
     * @return EXIF 처리 자리를 나타내는 예약 객체
     * @throws BusinessException Worker 자리가 없으면 RATE_LIMIT_EXCEEDED
     */
    public Permit reserve() {
        if (!availablePermits.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return new Permit();
    }

    /**
     * 미리 예약한 Worker에서 EXIF 작업을 실행하고 최대 5초간 기다립니다.
     *
     * 예약 객체는 실제 작업이 끝난 뒤 반환됩니다. Timeout 후 작업이 아직 중단되지 않았다면
     * Worker 수를 초과하지 않도록 작업 종료까지 자리를 유지합니다.
     *
     * @param permit reserve()에서 받은 예약 객체
     * @param task 실행할 EXIF 검증 작업
     * @param <T> 작업 결과 타입
     * @return 작업 결과
     * @throws TimeoutException 작업이 5초 안에 끝나지 않은 경우
     */
    public <T> T execute(Permit permit, Callable<T> task)
            throws TimeoutException {
        if (permit == null || !permit.markSubmitted()) {
            throw new IllegalStateException(
                    "유효하고 아직 사용하지 않은 Worker 예약이 필요합니다."
            );
        }

        FutureTask<T> future = new FutureTask<>(task);

        try {
            // 예약한 자리에서 작업을 실행하고, 실제 실행이 끝날 때 자리를 반환합니다.
            executor.execute(() -> {
                try {
                    future.run();
                } finally {
                    permit.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            // Executor 종료 등으로 실행이 거절되면 예약 자리를 즉시 반환합니다.
            permit.release();
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }

        try {
            return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            // 취소 신호를 보내고, 실제 Worker 자리는 작업 Runnable이 종료될 때 반환합니다.
            future.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ExifTaskExecutionException(cause);
        }
    }

    /**
     * Worker 이름을 구분하기 위한 스레드 팩토리를 만듭니다.
     */
    private ThreadFactory workerThreadFactory() {
        AtomicInteger sequence = new AtomicInteger(1);

        return task -> {
            Thread thread = new Thread(
                    task,
                    "photo-exif-worker-" + sequence.getAndIncrement()
            );
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 애플리케이션 종료 시 EXIF Worker를 정리합니다.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 아직 제출 전인 예약은 호출이 끝날 때 반환합니다.
     * execute()에 전달된 예약은 Worker 작업이 실제로 끝날 때 반환됩니다.
     */
    public final class Permit implements AutoCloseable {

        private final AtomicBoolean submitted = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);

        private boolean markSubmitted() {
            return !released.get() && submitted.compareAndSet(false, true);
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                availablePermits.release();
            }
        }

        @Override
        public void close() {
            // 예약만 하고 실행하지 않은 경우 자리를 반환합니다.
            if (!submitted.get()) {
                release();
            }
        }
    }

    /**
     * Worker에서 확인된 예외가 RuntimeException이 아닐 때 감쌉니다.
     */
    public static class ExifTaskExecutionException extends RuntimeException {
        public ExifTaskExecutionException(Throwable cause) {
            super("EXIF 작업 중 예기치 않은 오류가 발생했습니다.", cause);
        }
    }
}
