package com.team.sharedcreature.admin.integration;

import com.team.sharedcreature.admin.application.AdminDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 JPQL 집계와 제한된 KST 일별 SQL을 실제 MySQL 스키마에서 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class AdminDashboardIntegrationTest {

    @Autowired private AdminDashboardService dashboardService;

    /** 원본 데이터가 없어도 0 집계와 시즌 누락 경고를 안정적으로 반환합니다. */
    @Test
    void 빈_원본에서_대시보드와_시즌_누락_경고를_반환한다() {
        var response = dashboardService.get("7_DAYS", null);

        assertThat(response.summary().activeUsers()).isZero();
        assertThat(response.summary().questCompletionRate()).isEqualByComparingTo("0.00");
        assertThat(response.queues().operationalWarnings())
                .anyMatch(warning -> "SEASON_STATE_INVALID".equals(warning.type())
                        && warning.resourceId() == null);
    }
}
