package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 사진 정리 업무를 기존 Scheduler 실행권 체계에 연결합니다.
 * UTC 5분 구간을 논리 실행 시각으로 사용하고, 과거 틱은 소급 생성하지 않습니다.
 * 실제 파일·DB 정리는 PhotoRetentionWork의 구현체에 위임합니다.
 */
@Slf4j
@Service
public class PhotoRetentionNormalizationService {

    public static final String JOB_TYPE = "PHOTO_RETENTION_CLEANUP";
    private static final long INTERVAL_SECONDS = 300L;
    private static final Duration LEASE = Duration.ofMinutes(3);

    private final SchedulerJobRunner jobRunner;
    private final SchedulerExecutionLifecycleService lifecycle;
    private final PhotoRetentionWork work;
    private final Clock clock;
    private final String ownerId = "photo-retention-" + UUID.randomUUID();

    /**
     * 기존 Scheduler 실행권과 실제 사진 정리 서비스를 주입합니다.
     * 구현체가 누락된 상태에서 조용히 성공하는 일이 없도록 필수 의존성으로 받습니다.
     * 생성자에서 파일 정리나 실행권 선점은 수행하지 않습니다.
     */
    public PhotoRetentionNormalizationService(
            SchedulerJobRunner jobRunner,
            SchedulerExecutionLifecycleService lifecycle,
            PhotoRetentionWork work,
            Clock clock
    ) {
        this.jobRunner = jobRunner;
        this.lifecycle = lifecycle;
        this.work = work;
        this.clock = clock;
    }

    /**
     * 단일 구간의 Scheduler 처리 결과를 보존합니다.
     * SKIPPED는 이미 성공했거나 다른 인스턴스에서 RUNNING인 경우를 포함합니다.
     * 실패 시 원래 예외는 SchedulerJobRunner가 기록하고 여기에는 메시지를 보존합니다.
     */
    public record Result(Instant scheduledFor, SchedulerJobRunner.ExecutionResult execution,
                         PhotoRetentionWork.Summary summary, String error) { }

    /**
     * 기동 또는 5분 Cron에서 현재 사진 정리를 한 번 호출합니다.
     * 사진 정리가 부분 실패하면 Scheduler 실행 이력을 FAILED로 남기도록 예외를 전파합니다.
     * 다른 도메인 Scheduler까지 중단되지 않도록 호출 경계에서 결과를 보존합니다.
     */
    public Result normalize() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("사진 정리는 외부 트랜잭션 안에서 실행할 수 없습니다.");
        }
        Instant now = Instant.now(clock);
        Instant scheduledFor = Instant.ofEpochSecond(
                Math.floorDiv(now.getEpochSecond(), INTERVAL_SECONDS) * INTERVAL_SECONDS);
        AtomicReference<PhotoRetentionWork.Summary> summary = new AtomicReference<>();

        try {
            // 실행권을 선점한 호출만 사진 업무를 수행합니다.
            SchedulerJobRunner.ExecutionResult execution = jobRunner.run(
                    JOB_TYPE, scheduledFor, ownerId, LEASE, (SchedulerClaimTicket ticket) -> {
                        // 실제 사진 정리 항목의 진행 중에만 Lease를 연장합니다.
                        // 다른 인스턴스가 재선점했다면 갱신 실패 예외로 즉시 업무를 중단합니다.
                        PhotoRetentionWork.Summary outcome = Objects.requireNonNull(
                                work.cleanup(now, () -> lifecycle.renewLease(ticket, LEASE)),
                                "사진 정리 결과는 null일 수 없습니다.");
                        summary.set(outcome);
                        if (outcome.failedCount() > 0) {
                            throw new IllegalStateException("사진 정리 항목 실패: " + outcome.failedCount());
                        }
                        return toJson(outcome);
                    });
            return new Result(scheduledFor, execution, summary.get(), null);
        } catch (RuntimeException failure) {
            log.error("사진 정리 Scheduler 실행 실패. scheduledFor={}", scheduledFor, failure);
            return new Result(scheduledFor, null, summary.get(), failure.getMessage());
        }
    }

    /**
     * 사진 정리 건수와 잔여 작업 여부를 실행 이력 JSON으로 표현합니다.
     * 외부에서 입력받은 문자열을 삽입하지 않고 숫자와 불리언만 기록합니다.
     * 부분 실패는 앞선 검증에서 거절되어 이 성공 요약으로 기록되지 않습니다.
     */
    private String toJson(PhotoRetentionWork.Summary value) {
        return """
                {"pendingInvalidated":%d,"invalidFilesDeleted":%d,"validFilesDeleted":%d,\
                "failedCount":%d,"moreMayRemain":%s}
                """.formatted(value.pendingInvalidated(), value.invalidFilesDeleted(),
                value.validFilesDeleted(), value.failedCount(), value.moreMayRemain());
    }
}
