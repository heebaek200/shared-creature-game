package com.team.sharedcreature.season.scheduler;

import com.team.sharedcreature.quest.application.QuestAssignmentNormalizationService;
import com.team.sharedcreature.season.application.CreatureHatchingNormalizationService;
import com.team.sharedcreature.season.application.SeasonLifecycleNormalizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동, KST 자정, 5분 주기 트리거를 시즌 전체 정상화에 연결합니다.
 * 트리거는 시즌 종료·생성·그룹 초기화 같은 업무 상태를 직접 변경하지 않습니다.
 * 기존 클래스 이름을 유지해 중복된 @Scheduled Bean을 추가하지 않습니다.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true")
public class SeasonCloseScheduler {

    private final SeasonLifecycleNormalizationService normalizer;
    private final QuestAssignmentNormalizationService questNormalizer;
    private final CreatureHatchingNormalizationService hatchingNormalizer;

    /**
     * 종료와 신규 시즌 생성까지 담당하는 공통 정상화 Service를 주입받습니다.
     * 기존의 종료 전용 Service를 트리거에서 직접 사용하지 않습니다.
     * 이렇게 해야 모든 트리거가 같은 순서와 중복 실행 방어를 공유합니다.
     */
    public SeasonCloseScheduler(
            SeasonLifecycleNormalizationService normalizer,
            QuestAssignmentNormalizationService questNormalizer,
            CreatureHatchingNormalizationService hatchingNormalizer
    ) {
        this.normalizer = normalizer;
        this.questNormalizer = questNormalizer;
        this.hatchingNormalizer = hatchingNormalizer;
    }

    /**
     * 애플리케이션 준비 완료 후 누락된 시즌 업무를 복구합니다.
     * DB와 필수 기동 초기화가 끝난 시점에 한 번 호출됩니다.
     * 정상화 실패는 기록하고 다음 5분 재점검에서 재시도합니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runSafely("APPLICATION_READY");
    }

    /**
     * KST 자정에 현재 시즌 종료와 신규 구간 시작을 점검합니다.
     * 4주 경계가 아닌 자정에도 멱등하게 동작합니다.
     * 동시에 실행되는 5분 트리거는 정상화 Service에서 차단됩니다.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void onKstMidnight() {
        runSafely("KST_MIDNIGHT");
    }

    /**
     * 매 5분 경계마다 이전 실행에서 남은 그룹 초기화를 재개합니다.
     * 서버 중단·실패·Lease 만료 후에는 현재 저장 상태에 맞추어 복구합니다.
     * 이미 완료된 시즌과 그룹 초기화를 중복 실행하지 않습니다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void onEveryFiveMinutes() {
        runSafely("FIVE_MINUTES");
    }

    /**
     * 모든 트리거가 동일한 전체 정상화 메서드를 실행하도록 위임합니다.
     * 인프라 오류가 발생해도 기동·스케줄 스레드를 종료하지 않습니다.
     * 정상화 상태는 다음 실행의 운영 진단을 위해 로그에 기록합니다.
     */
    private void runSafely(String trigger) {
        try {
            // 각 업무 서비스는 별도의 트랜잭션에서 Commit됩니다.
            SeasonLifecycleNormalizationService.Result result = normalizer.normalize();
            log.info("시즌 전체 정상화. trigger={}, result={}", trigger, result);
            // 신규 그룹 초기화가 남아 있다면 퀘스트 배정은 다음 점검으로 미룹니다.
            if (result.outcome() != SeasonLifecycleNormalizationService.Outcome.ACTIVE) {
                return;
            }
            // 시즌의 ACTIVE 확정 뒤에만 현재 일일·주간 배정에 진입합니다.
            try {
                QuestAssignmentNormalizationService.Result quests =
                        questNormalizer.normalizeForActiveSeason(result.seasonId());
                log.info("퀘스트 기간 정상화. trigger={}, result={}", trigger, quests);
            } catch (RuntimeException error) {
                // 퀘스트 기간 복구 실패가 독립적인 크리처 부화까지 막지 않습니다.
                log.error("퀘스트 기간 정상화 오류. trigger={}", trigger, error);
            }
            try {
                // 부화 대상은 읽기 조회 후 기존 업무 서비스의 트랜잭션에서 확정합니다.
                CreatureHatchingNormalizationService.Result hatching =
                        hatchingNormalizer.normalizeForActiveSeason(result.seasonId());
                log.info("크리처 부화 정상화. trigger={}, result={}", trigger, hatching);
            } catch (RuntimeException error) {
                // 일시적인 DB 오류는 다음 5분 점검에서 다시 후보를 검색합니다.
                log.error("크리처 부화 정상화 오류. trigger={}", trigger, error);
            }
        } catch (RuntimeException error) {
            // 오류는 다음 기동·5분 점검에서 데이터 상태를 다시 읽어 복구합니다.
            log.error("시즌 전체 정상화 오류. trigger={}", trigger, error);
        }
    }
}
