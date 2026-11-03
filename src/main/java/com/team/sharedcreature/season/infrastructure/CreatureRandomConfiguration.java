package com.team.sharedcreature.season.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/** 운영 무작위 부화와 테스트 대체가 가능한 난수 공급자를 구성합니다. */
@Configuration
public class CreatureRandomConfiguration {

    /** 활성 크리처 종의 균등 무작위 선택에 사용할 공급자를 제공합니다. */
    @Bean
    RandomGenerator creatureRandomGenerator() {
        return new SecureRandom();
    }
}
