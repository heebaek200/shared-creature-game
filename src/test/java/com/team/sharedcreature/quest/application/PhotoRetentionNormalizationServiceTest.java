package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 사진 정리 실행권과 UTC 논리 시각 계산을 단위 테스트합니다.
 * SchedulerJobRunner는 Mock으로 대체하되 실제 업무 콜백을 직접 실행해 검증합니다.
 * 작업 서비스의 DB/파일 경계는 별도의 테스트에서 검증합니다.
 */
class PhotoRetentionNormalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:07:45Z");
    private static final Instant BUCKET = Instant.parse("2026-11-10T09:05:00Z");
    private final SchedulerJobRunner runner = mock(SchedulerJobRunner.class);
    private final PhotoRetentionWork work = mock(PhotoRetentionWork.class);
    private final PhotoRetentionNormalizationService service = new PhotoRetentionNormalizationService(
            runner, work, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * 현재 UTC 5분 구간의 실행권을 선점하고 실제 정리 콜백을 한 번 실행합니다.
     * 과거 논리 구간은 따로 만들지 않습니다.
     * 요약 JSON에는 성공한 정리 건수와 잔여 항목 여부가 기록됩니다.
     */
    @Test
    void executesOneCurrentUtcBucket() {
        PhotoRetentionWork.Summary summary = new PhotoRetentionWork.Summary(2, 3, 4, 0, true);
        when(work.cleanup(NOW)).thenReturn(summary);
        AtomicReference<String> json = new AtomicReference<>();
        when(runner.run(eq(PhotoRetentionNormalizationService.JOB_TYPE), eq(BUCKET), anyString(),
                eq(Duration.ofMinutes(3)), ArgumentMatchers.<Supplier<String>>any()))
                .thenAnswer(invocation -> {
                    Supplier<String> job = invocation.getArgument(4);
                    json.set(job.get());
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });

        var result = service.normalize();
        assertEquals(BUCKET, result.scheduledFor());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        assertSame(summary, result.summary());
        assertTrue(json.get().contains("\"validFilesDeleted\":4"));
        assertNull(result.error());
        verify(work).cleanup(NOW);
    }

    /**
     * SUCCEEDED와 다른 인스턴스 RUNNING은 모두 SKIPPED로 처리합니다.
     * 이 경우 실제 사진 업무는 호출하지 않습니다.
     * 외부에 존재하지 않는 성공 수치를 만들어 반환하지 않습니다.
     */
    @Test
    void skippedClaimNeverRunsCleanup() {
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);

        var result = service.normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.execution());
        assertNull(result.summary());
        assertNull(result.error());
        verifyNoInteractions(work);
    }

    /**
     * 한 건이라도 정리에 실패하면 해당 구간을 성공으로 표시하지 않습니다.
     * 실제 Runner는 실패 예외를 받아 실행 이력을 FAILED로 기록합니다.
     * 결과에는 처리했던 건수와 오류가 함께 남아야 합니다.
     */
    @Test
    void failedItemsCannotMarkJobSucceeded() {
        PhotoRetentionWork.Summary summary = new PhotoRetentionWork.Summary(1, 0, 0, 1, false);
        when(work.cleanup(NOW)).thenReturn(summary);
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());

        var result = service.normalize();
        assertNull(result.execution());
        assertSame(summary, result.summary());
        assertTrue(result.error().contains("사진 정리 항목 실패"));
    }

    /**
     * 외부 트랜잭션 안에서는 사진 삭제를 시작하지 않습니다.
     * 파일 삭제 중 DB 락을 유지하는 실수를 막기 위한 경계 테스트입니다.
     * 테스트에서 변경한 스레드 트랜잭션 플래그는 원래 상태로 복원합니다.
     */
    @Test
    void rejectsExistingTransaction() {
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, service::normalize);
            verifyNoInteractions(runner, work);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
    }

    /**
     * 비정상적인 음수 정리 건수는 결과 계약에서 거절합니다.
     * 스케줄 로그나 통계에 음수 값이 기록되지 않도록 합니다.
     * 정상적인 빈 작업은 모두 0으로 반환할 수 있습니다.
     */
    @Test
    void rejectsInvalidSummary() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhotoRetentionWork.Summary(-1, 0, 0, 0, false));
    }
}
