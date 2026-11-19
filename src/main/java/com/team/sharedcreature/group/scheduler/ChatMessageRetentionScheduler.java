package com.team.sharedcreature.group.scheduler;

import com.team.sharedcreature.group.application.ChatMessageRetentionJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매시간 만료 채팅을 정리하며 실패를 다른 Scheduler와 격리합니다. */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true")
public class ChatMessageRetentionScheduler {

    private final ChatMessageRetentionJob job;

    /** 실행권을 포함한 채팅 보존 Job을 주입받습니다. */
    public ChatMessageRetentionScheduler(ChatMessageRetentionJob job) {
        this.job = job;
    }

    /** 매시간 현재 만료 행을 삭제하고 실패를 다음 실행과 격리합니다. */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void deleteExpiredHourly() {
        try {
            log.info("채팅 만료 정리 완료. result={}", job.run());
        } catch (RuntimeException exception) {
            log.error("채팅 만료 정리 실패", exception);
        }
    }
}
