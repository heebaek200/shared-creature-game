package com.team.sharedcreature._core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 시즌 종료 스케줄러를 명시적으로 활성화할 때만 @Scheduled를 등록합니다.
 * test 프로필에서는 스케줄러를 사용하지 않아 DB 테스트를 보호합니다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true")
@EnableScheduling
public class SeasonSchedulingConfiguration {
}
