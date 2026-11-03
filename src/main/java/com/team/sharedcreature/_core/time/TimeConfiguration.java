package com.team.sharedcreature._core.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각 의존 로직이 테스트 가능한 공통 Clock을 사용하게 합니다.
 */
@Configuration
public class TimeConfiguration {

    /**
     * 데이터 저장과 API 시각의 기준인 UTC Clock을 제공합니다.
     *
     * @return 시스템 UTC Clock
     */
    @Bean
    Clock businessClock() {
        return Clock.systemUTC();
    }
}
