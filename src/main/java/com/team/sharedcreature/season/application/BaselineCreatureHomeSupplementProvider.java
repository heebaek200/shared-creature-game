package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.infrastructure.CreatureExperienceLedgerRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 연계 이슈 구현 전에도 실제 오늘 경험치와 유효한 빈 부가 목록을 제공하는 기본 Provider입니다.
 */
@Component
public class BaselineCreatureHomeSupplementProvider implements CreatureHomeSupplementProvider {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final CreatureExperienceLedgerRepository ledgerRepository;
    private final UUID streamInstanceId = UUID.randomUUID();

    /** 경험치 원장 조회 경계를 주입받습니다. */
    public BaselineCreatureHomeSupplementProvider(CreatureExperienceLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /** KST 오늘 경험치를 계산하고 아직 연결되지 않은 목록은 빈 Snapshot으로 반환합니다. */
    @Override
    public Supplement getSupplement(GroupCreature creature, Long requesterId, Instant now) {
        LocalDate businessDate = now.atZone(BUSINESS_ZONE).toLocalDate();
        Instant startsAt = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant endsAt = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        long todayExperience = ledgerRepository.sumExperienceByCreatureAndOccurredAt(
                creature.getId(), startsAt, endsAt);
        return new Supplement(todayExperience, List.of(), List.of(), streamInstanceId, 0L);
    }
}
