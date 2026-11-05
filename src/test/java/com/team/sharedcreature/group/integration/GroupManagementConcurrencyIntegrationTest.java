package com.team.sharedcreature.group.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * STEP 7 그룹 관리 명령의 실제 MySQL 동시성을 검증합니다.
 *
 * 각 요청은 별도 스레드에서 독립적인 트랜잭션으로 수행합니다.
 * 서비스의 행 잠금, UNIQUE 제약 및 최종 데이터 정합성을 확인합니다.
 * 테스트용 회원과 그룹은 독립적으로 생성하고 종료 후 정리합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_USERNAME",
        matches = ".+"
)
class GroupManagementConcurrencyIntegrationTest {
    // ...
}
