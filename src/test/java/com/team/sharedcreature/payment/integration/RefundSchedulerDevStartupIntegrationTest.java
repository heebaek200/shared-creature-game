package com.team.sharedcreature.payment.integration;

import org.junit.jupiter.api.Disabled;
import org.springframework.test.context.ActiveProfiles;

/**
 * dev 기본 설정의 기동 이벤트와 실제 UTC 5분 Cron을 검증합니다.
 * 부모의 테스트 DB 강제 설정·PG 대역·검증·정리 절차를 그대로 사용합니다.
 */
// 부모 테스트와 함께 실제 5분 Cron 대기를 임시로 제외합니다.
@Disabled("실제 5분 Cron 대기 테스트 임시 제외: 최종 Scheduler 검증 시 다시 활성화")
@ActiveProfiles(profiles = "dev", inheritProfiles = false)
class RefundSchedulerDevStartupIntegrationTest
        extends RefundSchedulerLocalStartupIntegrationTest {
}
