package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.application.BaselineCreatureHomeSupplementProvider;
import com.team.sharedcreature.season.application.CreatureHomeSupplementProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 연계 기능 Provider가 아직 없을 때만 최소 크리처 홈 부가 Snapshot을 구성합니다. */
@Configuration
public class CreatureHomeSupplementConfiguration {

    /** 실제 기여·Presence·Stream Provider가 등록되면 자동으로 대체되는 기본 구현입니다. */
    @Bean
    @ConditionalOnMissingBean(CreatureHomeSupplementProvider.class)
    CreatureHomeSupplementProvider baselineCreatureHomeSupplementProvider(
            CreatureExperienceLedgerRepository ledgerRepository
    ) {
        return new BaselineCreatureHomeSupplementProvider(ledgerRepository);
    }
}
