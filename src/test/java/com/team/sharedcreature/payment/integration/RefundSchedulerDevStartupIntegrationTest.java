package com.team.sharedcreature.payment.integration;

import org.springframework.test.context.ActiveProfiles;

/**
 * dev 기본 설정의 기동 이벤트와 실제 UTC 5분 Cron을 검증합니다.
 * 부모의 테스트 DB 강제 설정·PG 대역·검증·정리 절차를 그대로 사용합니다.
 */
@ActiveProfiles(profiles = "dev", inheritProfiles = false)
class RefundSchedulerDevStartupIntegrationTest
        extends RefundSchedulerLocalStartupIntegrationTest {
}
