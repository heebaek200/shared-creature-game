package com.team.sharedcreature.quest.scheduler;

import com.team.sharedcreature.quest.application.PhotoRetentionNormalizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #24 사진 정리의 기동 및 5분 주기 트리거입니다.
 * 시즌 Scheduler와 독립적으로 동작하지만 같은 SchedulerJobRunner 실행권을 사용합니다.
 * 테스트 및 연동 검증이 끝날 때까지 photo-retention-enabled=false로 유지합니다.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.scheduler", name = {"enabled", "photo-retention-enabled"}, havingValue = "true")
public class PhotoRetentionScheduler {

    private final PhotoRetentionNormalizationService normalizer;

    /**
     * 실제 데이터 정리는 정상화 서비스에만 위임합니다.
     * 이 트리거는 DB 트랜잭션을 열지 않습니다.
     * 기존 #18의 독립 @Scheduled를 제거한 뒤 이 클래스만 스케줄을 등록합니다.
     */
    public PhotoRetentionScheduler(PhotoRetentionNormalizationService normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * 서버가 기동될 때 과거 틱 생성 없이 현재 미처리 데이터를 점검합니다.
     * 5분 Cron과 같은 논리 구간이면 실행권으로 중복 실행을 막습니다.
     * 실패는 5분 트리거에서 다시 확인하도록 로그에 남깁니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runSafely("APPLICATION_READY");
    }

    /**
     * UTC 5분 경계마다 사진 정리를 시도합니다.
     * UTC 기준 실행권이므로 호스트의 로컬 시간대가 바뀌어도 논리 키가 일정합니다.
     * 매번 현재 조건을 다시 조회하며 과거 5분 틱은 소급 생성하지 않습니다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void onEveryFiveMinutes() {
        runSafely("FIVE_MINUTES");
    }

    /**
     * 사진 정리 장애를 다른 Scheduler 및 Spring 스케줄 스레드에서 격리합니다.
     * 정상화 결과의 FAILED·SKIPPED 구분을 로그로 확인할 수 있습니다.
     * 외부 DB 트랜잭션은 사용하지 않습니다.
     */
    private void runSafely(String trigger) {
        try {
            var result = normalizer.normalize();
            if (result.error() != null) {
                log.error("사진 정리 실패. trigger={}, result={}", trigger, result);
            } else {
                log.info("사진 정리 점검. trigger={}, result={}", trigger, result);
            }
        } catch (RuntimeException exception) {
            log.error("사진 정리 트리거 오류. trigger={}", trigger, exception);
        }
    }
}
