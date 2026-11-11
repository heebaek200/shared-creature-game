package com.team.sharedcreature._core.config;

import com.team.sharedcreature.payment.scheduler.PaymentWebhookReconciliationScheduler;
import com.team.sharedcreature.payment.scheduler.RefundReconciliationScheduler;
import com.team.sharedcreature.quest.scheduler.PhotoRetentionScheduler;
import com.team.sharedcreature.season.scheduler.SeasonCloseScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #24의 local/dev 자동 실행 설정과 test 비활성화 계약을 검증합니다.
 * 실제 Scheduler를 실행하거나 MySQL·PortOne을 호출하지 않는 순수 구성 검증입니다.
 * @Scheduled와 ApplicationReadyEvent가 누락되지 않았는지도 확인합니다.
 */
class SchedulerAutoActivationContractTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    /**
     * local 프로필에서 서버만 실행하면 시즌·사진·결제 작업이 자동으로 활성화됩니다.
     * 환불은 #22 업무 구현체가 아직 병합되지 않아 비활성화합니다.
     * 정기 실행 스레드 풀은 여러 5분 Cron이 겹칠 때의 병목을 줄입니다.
     */
    @Test
    void localEnablesReadyJobsWithoutManualFlags() throws IOException {
        PropertySource<?> local = yaml("application-local.yaml");

        // 운영 작업 세 가지는 true, 연동 전 환불 작업만 false여야 합니다.
        assertEquals(Boolean.TRUE, local.getProperty("app.scheduler.enabled"));
        assertEquals(Boolean.TRUE, local.getProperty("app.scheduler.photo-retention-enabled"));
        assertEquals(Boolean.TRUE, local.getProperty("app.scheduler.payment-webhook-enabled"));
        assertEquals(Boolean.FALSE, local.getProperty("app.scheduler.refund-reconciliation-enabled"));
        assertEquals(6, ((Number) local.getProperty("spring.task.scheduling.pool.size")).intValue());
    }

    /**
     * dev 프로필도 local과 동일한 자동 실행 정책을 기본값으로 사용합니다.
     * 사용자가 별도 활성화 환경변수를 입력하지 않아도 동작해야 합니다.
     * 환불은 아직 연결 전이므로 안전하게 꺼져 있어야 합니다.
     */
    @Test
    void devEnablesReadyJobsWithoutManualFlags() throws IOException {
        PropertySource<?> dev = yaml("application-dev.yaml");

        // 개발 서버도 기본 상태에서 실행 가능한 Scheduler만 활성화합니다.
        assertEquals(Boolean.TRUE, dev.getProperty("app.scheduler.enabled"));
        assertEquals(Boolean.TRUE, dev.getProperty("app.scheduler.photo-retention-enabled"));
        assertEquals(Boolean.TRUE, dev.getProperty("app.scheduler.payment-webhook-enabled"));
        assertEquals(Boolean.FALSE, dev.getProperty("app.scheduler.refund-reconciliation-enabled"));
        assertEquals(6, ((Number) dev.getProperty("spring.task.scheduling.pool.size")).intValue());
    }

    /**
     * test 프로필에서는 실제 기동 복구와 주기 작업을 자동으로 실행하면 안 됩니다.
     * 미지정 프로필의 기본 설정도 과도한 작업 실행을 방지하도록 비활성화합니다.
     * test에서 활성화 값을 직접 덮어써도 @Profile(!test)가 최종 차단합니다.
     */
    @Test
    void defaultAndTestProfilesKeepSchedulersOff() throws IOException {
        PropertySource<?> defaults = yaml("application.yaml");
        PropertySource<?> test = yaml("application-test.yaml");

        // 전체 Scheduler 스위치는 기본 및 테스트 환경에서 모두 false입니다.
        assertEquals(Boolean.FALSE, defaults.getProperty("app.scheduler.enabled"));
        assertEquals(Boolean.FALSE, test.getProperty("app.scheduler.enabled"));
    }

    /**
     * Spring이 자동 Cron을 등록할 진입점은 하나로 유지합니다.
     * 시즌·퀘스트·부화 트리거는 단일 SeasonCloseScheduler가 담당합니다.
     * 다른 프로필의 무조건 실행을 막는 조건부 등록을 검사합니다.
     */
    @Test
    void seasonSchedulingIsConditionalAndHasOneEnableConfiguration() {
        assertNotNull(SeasonSchedulingConfiguration.class.getAnnotation(EnableScheduling.class));
        assertProfileExcludesTest(SeasonSchedulingConfiguration.class);
        assertConditionalNames(SeasonSchedulingConfiguration.class, List.of("enabled"));
        assertProfileExcludesTest(SeasonCloseScheduler.class);
        assertConditionalNames(SeasonCloseScheduler.class, List.of("enabled"));
    }

    /**
     * 사진·결제 Scheduler는 전역 활성화와 각 기능 플래그를 동시에 요구합니다.
     * 환불은 #22 구현 이후 같은 조건을 활성화하는 별도 단계로 남깁니다.
     * 두 종류 이상 같은 5분 구간 작업을 독립적으로 등록할 수 있습니다.
     */
    @Test
    void domainSchedulersHaveFeatureGatesAndExcludeTest() {
        for (Class<?> scheduler : List.of(PhotoRetentionScheduler.class,
                PaymentWebhookReconciliationScheduler.class, RefundReconciliationScheduler.class)) {
            // 테스트에서 자동 실행되지 않도록 모든 Scheduler가 !test 프로필을 사용합니다.
            assertProfileExcludesTest(scheduler);
        }
        assertConditionalNames(PhotoRetentionScheduler.class,
                List.of("enabled", "photo-retention-enabled"));
        assertConditionalNames(PaymentWebhookReconciliationScheduler.class,
                List.of("enabled", "payment-webhook-enabled"));
        assertConditionalNames(RefundReconciliationScheduler.class,
                List.of("enabled", "refund-reconciliation-enabled"));
    }

    /**
     * 서버 기동과 5분 간격 실행의 애너테이션 계약이 유지되는지 검사합니다.
     * 시즌 정상화는 KST 자정에도 한 번 더 검사해야 합니다.
     * 실제 데이터 생성 여부는 local/dev 기동 실증에서 별도로 확인합니다.
     */
    @Test
    void everySchedulerHasStartupAndFiveMinuteTriggers() throws NoSuchMethodException {
        for (Class<?> scheduler : List.of(SeasonCloseScheduler.class,
                PhotoRetentionScheduler.class,
                PaymentWebhookReconciliationScheduler.class,
                RefundReconciliationScheduler.class)) {
            // ApplicationReadyEvent는 기동 시 누락 작업을 찾는 진입점입니다.
            assertNotNull(scheduler.getMethod("onApplicationReady").getAnnotation(EventListener.class));
            Scheduled cron = scheduler.getMethod("onEveryFiveMinutes").getAnnotation(Scheduled.class);
            assertNotNull(cron);
            assertEquals("0 */5 * * * *", cron.cron());
        }

        Method midnight = SeasonCloseScheduler.class.getMethod("onKstMidnight");
        assertEquals("0 0 0 * * *", midnight.getAnnotation(Scheduled.class).cron());
        assertEquals("Asia/Seoul", midnight.getAnnotation(Scheduled.class).zone());
        assertEquals("UTC", PhotoRetentionScheduler.class.getMethod("onEveryFiveMinutes")
                .getAnnotation(Scheduled.class).zone());
        assertEquals("UTC", PaymentWebhookReconciliationScheduler.class.getMethod("onEveryFiveMinutes")
                .getAnnotation(Scheduled.class).zone());
        assertEquals("UTC", RefundReconciliationScheduler.class.getMethod("onEveryFiveMinutes")
                .getAnnotation(Scheduled.class).zone());
    }

    /**
     * 클래스패스의 YAML을 Spring Boot가 사용하는 로더로 읽습니다.
     * 데이터베이스 접근이나 Spring ApplicationContext 기동 없이 순수 설정만 확인합니다.
     * 파일이 없거나 올바른 YAML이 아니면 테스트가 실패합니다.
     */
    private PropertySource<?> yaml(String fileName) throws IOException {
        var sources = loader.load(fileName, new ClassPathResource(fileName));
        assertEquals(1, sources.size(), "하나의 YAML 문서만 지원합니다: " + fileName);
        return sources.get(0);
    }

    /**
     * Scheduler의 프로필 조건에 test가 명시적으로 제외되어 있는지 검사합니다.
     * test 프로필에서 자동 DB 정리나 결제 복구가 실행되는 것을 방지합니다.
     * 이 검증은 실제 빈 등록 검증을 대신하지는 않습니다.
     */
    private void assertProfileExcludesTest(Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        assertNotNull(profile, type.getSimpleName() + " @Profile 누락");
        assertTrue(Arrays.asList(profile.value()).contains("!test"));
    }

    /**
     * @ConditionalOnProperty의 속성명과 활성화 값의 계약을 확인합니다.
     * 전역 스위치와 도메인별 스위치가 실수로 OR 조건이 되지 않도록 검사합니다.
     * 실제 Spring 평가 결과는 local/dev 기동 실증에서 확인합니다.
     */
    private void assertConditionalNames(Class<?> type, List<String> expectedNames) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, type.getSimpleName() + " @ConditionalOnProperty 누락");
        assertEquals("app.scheduler", condition.prefix());
        assertEquals("true", condition.havingValue());
        assertEquals(expectedNames, Arrays.asList(condition.name()));
    }
}
