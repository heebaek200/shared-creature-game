package com.team.sharedcreature.payment.scheduler;

import com.team.sharedcreature.payment.application.RefundReconciliationNormalizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 환불 상태 복구 Scheduler의 트리거 기능.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "app.scheduler",
        name = {
                "enabled",
                "refund-reconciliation-enabled"
        },
        havingValue = "true"
)
public class RefundReconciliationScheduler {

    private final RefundReconciliationNormalizationService normalizer;

    public RefundReconciliationScheduler(RefundReconciliationNormalizationService normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * 재기동 후 과거 5분 틱을 소급 생성하지 않고 현재 복구 대상만 점검한다.
     * 현재 UTC 5분 구간의 실행 이력을 SchedulerJobRunner로 재사용한다.
     * 서비스 오류가 나더라도 기동 이벤트를 중단시키지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runSafely("APPLICATION_READY");
    }

    /**
     * UTC 매 5분 경계에서 환불 취소 상태의 최신 조회를 요청합니다.
     * PG 취소 API를 직접 호출하지 않고 연계 서비스에 위임한다.
     * 다른 인스턴스와의 동일 구간 경합은 공통 실행권으로 방지한다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void onEveryFiveMinutes() {
        runSafely("FIVE_MINUTES");
    }

    /**
     * 스케줄 실행 오류를 별도 로그로 격리한다.
     * 업무 실패와 운영 실행 이력은 공통 Runner에서 기록된다.
     * 이 메서드에서 예외가 발생해도 다른 Cron 실행은 유지한다.
     */
    private void runSafely(String trigger) {
        try {
            // 실행권과 업무 복구는 항상 동일한 정규화 경로를 거친다.
            RefundReconciliationNormalizationService.Result result = normalizer.normalize();
            if (result.error() != null) {
                log.error("환불 복구 실패. trigger={}, result={}", trigger, result);
            } else {
                log.info("환불 복구 점검. trigger={}, result={}", trigger, result);
            }
        } catch (RuntimeException e) {
            log.error("환불 복구 트리거 오류. trigger={}", trigger, e);
        }
    }
}
