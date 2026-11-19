package com.team.sharedcreature.group.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 채팅 만료 삭제를 기존 Scheduler 실행권 체계에 연결합니다. */
@Service
public class ChatMessageRetentionJob {

    static final String JOB_TYPE = "CHAT_MESSAGE_RETENTION";
    private static final Duration LEASE = Duration.ofMinutes(10);

    private final SchedulerJobRunner jobRunner;
    private final ChatMessageCleanupService cleanupService;
    private final Clock clock;
    private final String ownerId = "chat-retention-" + UUID.randomUUID();

    /** 공통 실행권과 실제 정리 Service, 서버 시각을 주입받습니다. */
    public ChatMessageRetentionJob(
            SchedulerJobRunner jobRunner,
            ChatMessageCleanupService cleanupService,
            Clock clock
    ) {
        this.jobRunner = jobRunner;
        this.cleanupService = cleanupService;
        this.clock = clock;
    }

    /** 현재 UTC 시간 구간을 한 번 선점해 현재 만료 행을 모두 정리합니다. */
    public SchedulerJobRunner.ExecutionResult run() {
        Instant now = clock.instant();
        Instant scheduledFor = now.truncatedTo(ChronoUnit.HOURS);
        return jobRunner.run(JOB_TYPE, scheduledFor, ownerId, LEASE,
                ignored -> "{\"deletedCount\":" + cleanupService.deleteExpired(now) + "}");
    }
}
